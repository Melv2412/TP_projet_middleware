# Tests d'intégration de l'étape 2 (T00, T01, T02, T10, T11).
# Prérequis : "docker compose up -d" lancé et le bus démarré.
# Usage : powershell -ExecutionPolicy Bypass -File scripts\test-etape2.ps1

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

function Wait-Status($orderId, $expected) {
    for ($i = 0; $i -lt 20; $i++) {
        $o = Invoke-RestMethod "$base/api/orders/$orderId"
        if ($o.status -eq $expected -or ($expected -eq "INVOICED" -and $o.status -in $script:Invoiced)) { return $o }
        Start-Sleep -Milliseconds 500
    }
    return $o
}

function Stock($sku) {
    ((Invoke-RestMethod "$base/api/erp/inventory") | Where-Object { $_.sku -eq $sku }).available
}

function Post-Status($body) {
    try { Post-Order $body | Out-Null; return 202 }
    catch { return [int]$_.Exception.Response.StatusCode }
}

Write-Host "`n== T00 : santé du bus"
$health = Invoke-RestMethod "$base/actuator/health"
Check "Bus UP (PostgreSQL + RabbitMQ + Camel)" ($health.status -eq "UP") $health.status

Invoke-RestMethod -Method Post "$base/api/demo/reset" | Out-Null

Write-Host "`n== T01 : commande nominale TV + Frigo"
$r = Post-Order '{"customerId":"CLI-001","shippingAddress":"Cocody, Abidjan","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}'
$o = Wait-Status $r.orderId "INVOICED"
Check "Statut INVOICED" ($o.status -in $script:Invoiced) $o.status
Check "Total 775 000 FCFA" ($o.total -eq 775000) $o.total
Check "Stock TV = 9" ((Stock "TV-SAM-55") -eq 9) (Stock "TV-SAM-55")
Check "Stock Frigo = 5" ((Stock "FR-LG-01") -eq 5) (Stock "FR-LG-01")
$erp = Invoke-RestMethod "$base/api/erp/orders/$($r.orderId)"
Check "XML reçu par l'ERP" ($erp.xml_payload -like "<commande><ref>$($r.orderId)</ref>*") $erp.xml_payload
Check "Facture émise" ($erp.invoice.total -eq 775000) $erp.invoice
$events = Invoke-RestMethod "$base/api/orders/$($r.orderId)/events"
$patterns = $events.pattern
Check "Splitter utilisé" ($patterns -contains "Splitter") ($patterns -join ", ")
Check "Aggregator utilisé" ($patterns -contains "Aggregator") ($patterns -join ", ")
Check "Content-Based Router utilisé" ($patterns -contains "Content-Based Router") ($patterns -join ", ")
Check "Message Translator utilisé" ($patterns -contains "Message Translator") ($patterns -join ", ")
Check "Un seul correlationId" ((($events.correlation_id | Sort-Object -Unique) | Measure-Object).Count -eq 1) ""

Write-Host "`n== T02 : rupture de stock (5 machines à laver, stock 2)"
$r = Post-Order '{"customerId":"CLI-002","items":[{"sku":"LAVE-01","quantity":5}]}'
$o = Wait-Status $r.orderId "REJECTED_OUT_OF_STOCK"
Check "Statut REJECTED_OUT_OF_STOCK" ($o.status -eq "REJECTED_OUT_OF_STOCK") $o.status
Check "Stock machines à laver inchangé (2)" ((Stock "LAVE-01") -eq 2) (Stock "LAVE-01")
$erpStatus = try { Invoke-RestMethod "$base/api/erp/orders/$($r.orderId)" | Out-Null; 200 } catch { [int]$_.Exception.Response.StatusCode }
Check "Aucune commande / facture dans l'ERP" ($erpStatus -eq 404) $erpStatus

Write-Host "`n== T11 : article inconnu"
$r = Post-Order '{"customerId":"CLI-003","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"XBOX-99","quantity":1}]}'
$o = Wait-Status $r.orderId "REJECTED_OUT_OF_STOCK"
Check "Commande refusée" ($o.status -eq "REJECTED_OUT_OF_STOCK") $o.status
Check "Stock TV inchangé (9)" ((Stock "TV-SAM-55") -eq 9) (Stock "TV-SAM-55")

Write-Host "`n== T10 : validation des entrées"
Check "Commande vide -> 400" ((Post-Status '{"items":[]}') -eq 400) ""
Check "Quantité 0 -> 400" ((Post-Status '{"items":[{"sku":"TV-SAM-55","quantity":0}]}') -eq 400) ""
Check "JSON cassé -> 400" ((Post-Status '{"items":[{') -eq 400) ""

Write-Host ""
if ($script:failures -eq 0) { Write-Host "Tous les tests de l'étape 2 passent." -ForegroundColor Green }
else { Write-Host "$($script:failures) test(s) en échec." -ForegroundColor Red; exit 1 }
