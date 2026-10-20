# Tests d'intégration de l'étape 3 : idempotence (T06) et concurrence sur le stock (T09).
# Prérequis : "docker compose up -d" lancé et le bus démarré.
# Usage : powershell -ExecutionPolicy Bypass -File scripts\test-etape3.ps1

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$rabbit = "http://localhost:15672"
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

function Wait-Final($orderId) {
    for ($i = 0; $i -lt 30; $i++) {
        $o = Invoke-RestMethod "$base/api/orders/$orderId"
        if ($o.status -in ($script:Invoiced + @("REJECTED_OUT_OF_STOCK", "FAILED"))) { return $o }
        Start-Sleep -Milliseconds 500
    }
    return $o
}

function Stock($sku) {
    ((Invoke-RestMethod "$base/api/erp/inventory") | Where-Object { $_.sku -eq $sku }).available
}

function Count-Events($orderId, $status) {
    @((Invoke-RestMethod "$base/api/orders/$orderId/events") | Where-Object { $_.status -eq $status }).Count
}

Invoke-RestMethod -Method Post "$base/api/demo/reset" | Out-Null

Write-Host "`n== T06 : même message envoyé deux fois"
$r = Post-Order '{"customerId":"CLI-010","items":[{"sku":"TV-SAM-55","quantity":2}]}'
$o = Wait-Final $r.orderId
Check "Première commande facturée" ($o.status -in $script:Invoiced) $o.status
Check "Stock TV = 8 après la commande" ((Stock "TV-SAM-55") -eq 8) (Stock "TV-SAM-55")

# Doublon n°1 : renvoyé par le bus (simulation d'un renvoi réseau)
Invoke-RestMethod -Method Post "$base/api/demo/orders/$($r.orderId)/duplicate" | Out-Null

# Doublon n°2 : publié directement dans RabbitMQ, sans passer par le bus
$store = @{ orderId = $o.order_id; correlationId = $o.correlation_id; customerId = "CLI-010";
            items = @(@{ sku = "TV-SAM-55"; quantity = 2 }) } | ConvertTo-Json -Compress -Depth 5
$publish = @{ properties = @{ delivery_mode = 2 }; routing_key = "order.submitted";
              payload = $store; payload_encoding = "string" } | ConvertTo-Json -Compress -Depth 5
$rabbitUser = New-Object System.Management.Automation.PSCredential("sidam", (ConvertTo-SecureString "sidam" -AsPlainText -Force))
$res = Invoke-RestMethod -Method Post "$rabbit/api/exchanges/%2F/sidam.exchange/publish" `
    -Credential $rabbitUser -ContentType "application/json" -Body $publish
Check "Doublon publié directement dans RabbitMQ" ($res.routed -eq $true) $res

Start-Sleep -Seconds 3
Check "Les 2 doublons sont ignorés (DUPLICATE_IGNORED x2)" ((Count-Events $r.orderId "DUPLICATE_IGNORED") -eq 2) (Count-Events $r.orderId "DUPLICATE_IGNORED")
Check "Stock TV toujours 8 (pas de double réservation)" ((Stock "TV-SAM-55") -eq 8) (Stock "TV-SAM-55")
Check "Une seule facture (INVOICED x1)" ((Count-Events $r.orderId "INVOICED") -eq 1) (Count-Events $r.orderId "INVOICED")
$o = Invoke-RestMethod "$base/api/orders/$($r.orderId)"
Check "Statut toujours INVOICED" ($o.status -in $script:Invoiced) $o.status

Write-Host "`n== T09 : 10 commandes simultanées pour 3 frigos en stock"
Invoke-RestMethod -Method Post "$base/api/demo/stock/FR-LG-01?available=3" | Out-Null
$jobs = 1..10 | ForEach-Object {
    Start-Job -ArgumentList $base -ScriptBlock {
        param($b)
        Invoke-RestMethod -Method Post -Uri "$b/api/orders" -ContentType "application/json" `
            -Body '{"customerId":"CLI-RUSH","items":[{"sku":"FR-LG-01","quantity":1}]}'
    }
}
$ids = $jobs | Wait-Job | Receive-Job | ForEach-Object { $_.orderId }
$jobs | Remove-Job
Check "10 commandes reçues" ($ids.Count -eq 10) $ids.Count

$final = $ids | ForEach-Object { Wait-Final $_ }
$accepted = @($final | Where-Object { $_.status -in $script:Invoiced }).Count
$rejected = @($final | Where-Object { $_.status -eq "REJECTED_OUT_OF_STOCK" }).Count
Write-Host "     acceptées : $accepted, refusées : $rejected"
Check "Exactement 3 commandes acceptées" ($accepted -eq 3) $accepted
Check "Exactement 7 commandes refusées" ($rejected -eq 7) $rejected
Check "Stock frigo = 0, jamais négatif" ((Stock "FR-LG-01") -eq 0) (Stock "FR-LG-01")

Write-Host ""
if ($script:failures -eq 0) { Write-Host "Tous les tests de l'étape 3 passent." -ForegroundColor Green }
else { Write-Host "$($script:failures) test(s) en échec." -ForegroundColor Red; exit 1 }
