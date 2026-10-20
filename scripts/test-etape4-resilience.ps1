# Tests d'intégration de l'étape 4 : résilience.
#   T03 panne ERP -> Retry -> DLQ      T04 reprise + Replay, traité une seule fois
#   T07 import CSV                      T08 message invalide isolé en DLQ
# Prérequis : "docker compose up -d" lancé et le bus démarré.
# Usage : powershell -ExecutionPolicy Bypass -File scripts\test-etape4-resilience.ps1

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$script:failures = 0
# Depuis l'étape 5, une commande facturée continue vers la livraison.
$script:Invoiced = @("INVOICED", "DELIVERY_ASSIGNED", "IN_DELIVERY", "DELIVERED")

function Check($name, $condition, $detail) {
    if ($condition) { Write-Host "  [OK]    $name" -ForegroundColor Green }
    else { Write-Host "  [ECHEC] $name -> $detail" -ForegroundColor Red; $script:failures++ }
}

function Post-Order($body) {
    Invoke-RestMethod -Method Post -Uri "$base/api/orders" -ContentType "application/json" -Body $body
}

function Wait-Status($orderId, $expected, $seconds = 30) {
    for ($i = 0; $i -lt ($seconds * 2); $i++) {
        $o = Invoke-RestMethod "$base/api/orders/$orderId"
        if ($o.status -eq $expected -or ($expected -eq "INVOICED" -and $o.status -in $script:Invoiced)) { return $o }
        Start-Sleep -Milliseconds 500
    }
    return $o
}

function Stock($sku) {
    ((Invoke-RestMethod "$base/api/erp/inventory") | Where-Object { $_.sku -eq $sku }).available
}

function Events($orderId, $status) {
    # La virgule empêche PowerShell de "déballer" une liste d'un seul élément.
    return ,@((Invoke-RestMethod "$base/api/orders/$orderId/events") | Where-Object { $_.status -eq $status })
}

# Nombre de messages réellement présents dans la file RabbitMQ sidam.orders.dlq (lu en AMQP par le bus).
function Dlq-Depth() {
    (Invoke-RestMethod "$base/api/dlq").queueDepth
}

function Http-Status($scriptBlock) {
    try { & $scriptBlock | Out-Null; return 200 } catch { return [int]$_.Exception.Response.StatusCode }
}

Invoke-RestMethod -Method Post "$base/api/demo/reset" | Out-Null

Write-Host "`n== T03 : panne de l'ERP pendant une commande"
Invoke-RestMethod -Method Post "$base/api/demo/erp/down" | Out-Null
$r = Post-Order '{"customerId":"CLI-020","items":[{"sku":"TV-SAM-55","quantity":1}]}'
Write-Host "     $($r.orderId) envoyée ERP coupé, attente des 3 tentatives (2 s + 4 s + 8 s)..."
$o = Wait-Status $r.orderId "FAILED" 40
Check "Commande en échec (FAILED) après les tentatives" ($o.status -eq "FAILED") $o.status
$retries = Events $r.orderId "RETRYING"
Check "3 tentatives tracées (RETRYING x3)" ($retries.Count -eq 3) $retries.Count
Check "Tentatives numérotées 1, 2, 3" ((($retries.attempt) -join ",") -eq "1,2,3") (($retries.attempt) -join ",")
Check "Événement DEAD_LETTERED" ((Events $r.orderId "DEAD_LETTERED").Count -eq 1) ""
Check "Message présent dans la file RabbitMQ sidam.orders.dlq" ((Dlq-Depth) -eq 1) (Dlq-Depth)
$dlq = Invoke-RestMethod "$base/api/dlq"
$entry = @($dlq.messages | Where-Object { $_.order_id -eq $r.orderId })[0]
Check "DLQ : code ERP_UNAVAILABLE" ($entry.error_code -eq "ERP_UNAVAILABLE") $entry.error_code
Check "Stock TV inchangé (10)" ((Stock "TV-SAM-55") -eq 10) (Stock "TV-SAM-55")

Write-Host "`n== T04 : ERP rétabli, Replay depuis la DLQ"
Invoke-RestMethod -Method Post "$base/api/demo/erp/up" | Out-Null
Invoke-RestMethod -Method Post "$base/api/dlq/$($entry.dlq_id)/replay" | Out-Null
$o = Wait-Status $r.orderId "INVOICED"
Check "Commande rejouée puis facturée" ($o.status -in $script:Invoiced) $o.status
Check "Stock TV = 9 (débité une seule fois)" ((Stock "TV-SAM-55") -eq 9) (Stock "TV-SAM-55")
Check "Une seule facture" ((Events $r.orderId "INVOICED").Count -eq 1) (Events $r.orderId "INVOICED").Count
$second = Http-Status { Invoke-RestMethod -Method Post "$base/api/dlq/$($entry.dlq_id)/replay" }
Check "Second Replay refusé (409)" ($second -eq 409) $second
Invoke-RestMethod -Method Post "$base/api/demo/orders/$($r.orderId)/duplicate" | Out-Null
Start-Sleep -Seconds 3
Check "Doublon après Replay ignoré, stock toujours 9" ((Stock "TV-SAM-55") -eq 9) (Stock "TV-SAM-55")
Check "DLQ RabbitMQ vide" ((Dlq-Depth) -eq 0) (Dlq-Depth)

Write-Host "`n== T03 bis : reprise automatique si l'ERP revient pendant les tentatives"
Invoke-RestMethod -Method Post "$base/api/demo/erp/down" | Out-Null
$r = Post-Order '{"customerId":"CLI-021","items":[{"sku":"FR-LG-01","quantity":1}]}'
Start-Sleep -Seconds 3
Invoke-RestMethod -Method Post "$base/api/demo/erp/up" | Out-Null
$o = Wait-Status $r.orderId "INVOICED"
Check "Commande facturée sans intervention" ($o.status -in $script:Invoiced) $o.status
Check "Au moins une tentative tracée" ((Events $r.orderId "RETRYING").Count -ge 1) ""
Check "Pas de passage en DLQ" ((Events $r.orderId "DEAD_LETTERED").Count -eq 0) ""

Write-Host "`n== T07 : import CSV (format historique ERP)"
$csv = "order_id,sku,quantity,unit_price`nCMD-2026-0042,TV-SAM-55,1,350000`nCMD-2026-0042,FR-LG-01,1,425000`nCMD-2026-0043,LAVE-01,1,280000"
$imp = Invoke-RestMethod -Method Post "$base/api/orders/import-csv" -ContentType "text/csv" -Body $csv
Check "2 commandes créées depuis 3 lignes" (@($imp.imported).Count -eq 2) (@($imp.imported).Count)
$first = @($imp.imported | Where-Object { $_.csvOrderId -eq "CMD-2026-0042" })[0]
$o = Wait-Status $first.orderId "INVOICED"
Check "Commande CSV CMD-2026-0042 facturée" ($o.status -in $script:Invoiced) $o.status
Check "Total recalculé avec les prix ERP (775 000)" ($o.total -eq 775000) $o.total
$received = (Events $first.orderId "RECEIVED")[0]
Check "Payload CSV conservé pour l'inspecteur" ($received.payload_format -eq "CSV") $received.payload_format
$bad = Http-Status { Invoke-RestMethod -Method Post "$base/api/orders/import-csv" -ContentType "text/csv" -Body "order_id,sku,quantity,unit_price`nCMD-X,TV-SAM-55,deux,1" }
Check "CSV avec quantité invalide -> 400" ($bad -eq 400) $bad
$bad = Http-Status { Invoke-RestMethod -Method Post "$base/api/orders/import-csv" -ContentType "text/csv" -Body "a,b`n1,2" }
Check "CSV avec mauvais en-tête -> 400" ($bad -eq 400) $bad

Write-Host "`n== T08 : messages invalides injectés dans RabbitMQ"
$before = @((Invoke-RestMethod "$base/api/dlq").messages).Count
Invoke-RestMethod -Method Post "$base/api/demo/corrupt-message?type=malformed" | Out-Null
Invoke-RestMethod -Method Post "$base/api/demo/corrupt-message?type=incomplete" | Out-Null
Start-Sleep -Seconds 3
$dlq = Invoke-RestMethod "$base/api/dlq"
$new = @($dlq.messages | Where-Object { $_.error_code -eq "INVALID_PAYLOAD" })
Check "2 messages isolés en DLQ (INVALID_PAYLOAD)" ($new.Count -eq 2) $new.Count
Check "Isolés sans nouvelle tentative (immédiat)" (@($dlq.messages).Count -eq $before + 2) (@($dlq.messages).Count)
Check "Stock inchangé (TV 8, Frigo 4, Lave 1)" (((Stock "TV-SAM-55") -eq 8) -and ((Stock "FR-LG-01") -eq 4) -and ((Stock "LAVE-01") -eq 1)) "TV=$(Stock 'TV-SAM-55') FR=$(Stock 'FR-LG-01') LAVE=$(Stock 'LAVE-01')"

Write-Host ""
if ($script:failures -eq 0) { Write-Host "Tous les tests de l'étape 4 passent." -ForegroundColor Green }
else { Write-Host "$($script:failures) test(s) en échec." -ForegroundColor Red; exit 1 }
