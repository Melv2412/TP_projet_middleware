# Tests d'intégration de l'étape 5 : livraison (T05).
# Prérequis : "docker compose up -d" lancé et le bus démarré.
# Usage : powershell -ExecutionPolicy Bypass -File scripts\test-etape5.ps1

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$rabbit = "http://localhost:15672"
$rabbitUser = New-Object System.Management.Automation.PSCredential("sidam", (ConvertTo-SecureString "sidam" -AsPlainText -Force))
$script:failures = 0

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
        if ($o.status -eq $expected) { return $o }
        Start-Sleep -Milliseconds 500
    }
    return $o
}

function Inventory($sku) {
    ((Invoke-RestMethod "$base/api/erp/inventory") | Where-Object { $_.sku -eq $sku })
}

function Events($orderId, $status) {
    return ,@((Invoke-RestMethod "$base/api/orders/$orderId/events") | Where-Object { $_.status -eq $status })
}

function Http-Status($scriptBlock) {
    try { & $scriptBlock | Out-Null; return 200 } catch { return [int]$_.Exception.Response.StatusCode }
}

function Publish-Status($json) {
    $publish = @{ properties = @{ delivery_mode = 2; content_type = "application/json" }; routing_key = "delivery.status";
                  payload = $json; payload_encoding = "string" } | ConvertTo-Json -Compress -Depth 5
    Invoke-RestMethod -Method Post "$rabbit/api/exchanges/%2F/sidam.exchange/publish" `
        -Credential $rabbitUser -ContentType "application/json" -Body $publish | Out-Null
}

Invoke-RestMethod -Method Post "$base/api/demo/reset" | Out-Null

Write-Host "`n== T05 : commande facturée -> mission -> prise en charge -> livrée"
$r = Post-Order '{"customerId":"CLI-001","shippingAddress":"Cocody, Abidjan","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}'
$o = Wait-Status $r.orderId "DELIVERY_ASSIGNED"
Check "Mission créée automatiquement (DELIVERY_ASSIGNED)" ($o.status -eq "DELIVERY_ASSIGNED") $o.status
$m = Invoke-RestMethod "$base/api/deliveries/by-order/$($r.orderId)"
Check "Mission visible dans SIDAM Delivery ($($m.mission_id))" ($m.status -eq "ASSIGNED") $m.status
Check "Adresse transmise" ($m.address -eq "Cocody, Abidjan") $m.address
$parcels = $m.parcels | ConvertFrom-Json
Check "Colis avec désignations ERP" ($m.parcels -like "*""designation"":""Réfrigérateur LG""*") $m.parcels
Check "Stock TV réservé (dispo 9, réservé 1)" (((Inventory "TV-SAM-55").available -eq 9) -and ((Inventory "TV-SAM-55").reserved -eq 1)) ""

$early = Http-Status { Invoke-RestMethod -Method Post "$base/api/deliveries/$($m.mission_id)/confirm" }
Check "Confirmer avant la prise en charge -> 409" ($early -eq 409) $early

Invoke-RestMethod -Method Post "$base/api/deliveries/$($m.mission_id)/pickup" -ContentType "application/json" -Body '{"driver":"Koffi"}' | Out-Null
$o = Wait-Status $r.orderId "IN_DELIVERY"
Check "Bus : IN_DELIVERY" ($o.status -eq "IN_DELIVERY") $o.status

Invoke-RestMethod -Method Post "$base/api/deliveries/$($m.mission_id)/confirm" | Out-Null
$o = Wait-Status $r.orderId "DELIVERED"
Check "Bus / Store : DELIVERED" ($o.status -eq "DELIVERED") $o.status
$erp = Invoke-RestMethod "$base/api/erp/orders/$($r.orderId)"
Check "ERP : commande DELIVERED" ($erp.status -eq "DELIVERED") $erp.status
Check "ERP : stock réservé sorti (TV réservé 0, dispo 9)" (((Inventory "TV-SAM-55").reserved -eq 0) -and ((Inventory "TV-SAM-55").available -eq 9)) ""
Check "Avis de livraison XML transmis à l'ERP" (@((Events $r.orderId "TRANSFORMED") | Where-Object { $_.payload -like "<livraison>*" }).Count -eq 1) ""
Check "WORKFLOW_COMPLETED" ((Events $r.orderId "WORKFLOW_COMPLETED").Count -eq 1) ""

$timeline = (Invoke-RestMethod "$base/api/orders/$($r.orderId)/events").status
$expected = @("RECEIVED", "TRANSFORMED", "STOCK_RESERVED", "INVOICED", "DISPATCHED", "DELIVERY_ASSIGNED", "IN_DELIVERY", "DELIVERED", "WORKFLOW_COMPLETED")
$positions = $expected | ForEach-Object { [array]::IndexOf($timeline, $_) }
$ordered = ($positions -notcontains -1) -and (($positions | Sort-Object) -join ",") -eq ($positions -join ",")
Check "Timeline dans l'ordre : RECEIVED -> ... -> DELIVERED" $ordered ($timeline -join " > ")
$corr = @((Invoke-RestMethod "$base/api/orders/$($r.orderId)/events").correlation_id | Sort-Object -Unique)
Check "Même correlationId de la commande à la livraison" ($corr.Count -eq 1) ($corr -join ", ")

Write-Host "`n== Statuts en double ou en retard (Idempotent Receiver / Process Manager)"
$again = Http-Status { Invoke-RestMethod -Method Post "$base/api/deliveries/$($m.mission_id)/confirm" }
Check "Seconde confirmation refusée par l'app (409)" ($again -eq 409) $again
Publish-Status ("{""missionId"":""$($m.mission_id)"",""orderId"":""$($r.orderId)"",""status"":""DELIVERED"",""driver"":""Koffi""}")
Publish-Status ("{""missionId"":""$($m.mission_id)"",""orderId"":""$($r.orderId)"",""status"":""IN_DELIVERY"",""driver"":""Koffi""}")
Start-Sleep -Seconds 3
Check "DELIVERED en double + IN_DELIVERY en retard ignorés" ((Events $r.orderId "DUPLICATE_IGNORED").Count -eq 2) (Events $r.orderId "DUPLICATE_IGNORED").Count
$o = Invoke-RestMethod "$base/api/orders/$($r.orderId)"
Check "Statut toujours DELIVERED" ($o.status -eq "DELIVERED") $o.status
Check "Stock réservé pas décrémenté deux fois (FR réservé 0)" ((Inventory "FR-LG-01").reserved -eq 0) (Inventory "FR-LG-01").reserved

Write-Host "`n== Commande refusée : aucune mission"
$r2 = Post-Order '{"customerId":"CLI-002","items":[{"sku":"LAVE-01","quantity":5}]}'
Wait-Status $r2.orderId "REJECTED_OUT_OF_STOCK" | Out-Null
Start-Sleep -Seconds 2
$none = Http-Status { Invoke-RestMethod "$base/api/deliveries/by-order/$($r2.orderId)" }
Check "Pas de mission pour une commande en rupture (404)" ($none -eq 404) $none

Write-Host "`n== ERP en panne au moment de la livraison -> Retry -> DLQ -> Replay"
$r3 = Post-Order '{"customerId":"CLI-003","shippingAddress":"Plateau","items":[{"sku":"LAVE-01","quantity":1}]}'
Wait-Status $r3.orderId "DELIVERY_ASSIGNED" | Out-Null
$m3 = Invoke-RestMethod "$base/api/deliveries/by-order/$($r3.orderId)"
Invoke-RestMethod -Method Post "$base/api/deliveries/$($m3.mission_id)/pickup" | Out-Null
Wait-Status $r3.orderId "IN_DELIVERY" | Out-Null
Invoke-RestMethod -Method Post "$base/api/demo/erp/down" | Out-Null
Invoke-RestMethod -Method Post "$base/api/deliveries/$($m3.mission_id)/confirm" | Out-Null
Write-Host "     confirmation envoyée ERP coupé, attente des 3 tentatives..."
$entry = $null
for ($i = 0; $i -lt 40 -and -not $entry; $i++) {
    Start-Sleep -Seconds 1
    $entry = @((Invoke-RestMethod "$base/api/dlq").messages | Where-Object { $_.order_id -eq $r3.orderId })[0]
}
Check "Statut DELIVERED isolé en DLQ" ($null -ne $entry) ""
Check "File de rejeu = delivery.status" ($entry.replay_routing_key -eq "delivery.status") $entry.replay_routing_key
$o = Invoke-RestMethod "$base/api/orders/$($r3.orderId)"
Check "Commande restée IN_DELIVERY (pas FAILED)" ($o.status -eq "IN_DELIVERY") $o.status
Invoke-RestMethod -Method Post "$base/api/demo/erp/up" | Out-Null
Invoke-RestMethod -Method Post "$base/api/dlq/$($entry.dlq_id)/replay" | Out-Null
$o = Wait-Status $r3.orderId "DELIVERED"
Check "Après Replay : DELIVERED" ($o.status -eq "DELIVERED") $o.status
$erp = Invoke-RestMethod "$base/api/erp/orders/$($r3.orderId)"
Check "ERP : DELIVERED" ($erp.status -eq "DELIVERED") $erp.status

Write-Host ""
if ($script:failures -eq 0) { Write-Host "Tous les tests de l'étape 5 passent." -ForegroundColor Green }
else { Write-Host "$($script:failures) test(s) en échec." -ForegroundColor Red; exit 1 }
