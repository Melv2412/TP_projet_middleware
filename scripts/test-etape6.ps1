# Tests d'intégration de l'étape 6 : interfaces web et temps réel.
# Prérequis : "docker compose up -d" lancé et le bus démarré.
# Usage : powershell -ExecutionPolicy Bypass -File scripts\test-etape6.ps1

$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$script:failures = 0

function Check($name, $condition, $detail) {
    if ($condition) { Write-Host "  [OK]    $name" -ForegroundColor Green }
    else { Write-Host "  [ECHEC] $name -> $detail" -ForegroundColor Red; $script:failures++ }
}

function Http-Status($scriptBlock) {
    try { & $scriptBlock | Out-Null; return 200 } catch { return [int]$_.Exception.Response.StatusCode }
}

Invoke-RestMethod -Method Post "$base/api/demo/reset" | Out-Null

Write-Host "`n== Pages servies par Spring Boot (sans dépendance Internet)"
$pages = @{ "/" = "SIDAM CONNECT"; "/tower.html" = "SIDAM CONTROL TOWER"; "/store.html" = "SIDAM Store";
            "/erp.html" = "SIDAM-ERP"; "/delivery.html" = "SIDAM Delivery" }
foreach ($p in $pages.Keys) {
    $r = Invoke-WebRequest -UseBasicParsing "$base$p"
    Check "$p" (($r.StatusCode -eq 200) -and ($r.Content -like "*$($pages[$p])*")) $r.StatusCode
}
$tw = Invoke-WebRequest -UseBasicParsing "$base/vendor/tailwind.js"
Check "Tailwind servi localement ($([math]::Round($tw.RawContentLength / 1KB)) Ko)" ($tw.RawContentLength -gt 300000) $tw.RawContentLength
Check "sidam.js servi" ((Invoke-WebRequest -UseBasicParsing "$base/assets/sidam.js").StatusCode -eq 200) ""

Write-Host "`n== API des écrans"
$cat = @((Invoke-RestMethod "$base/api/catalog"))
Check "Catalogue Store : 3 produits avec prix et stock" (($cat.Count -eq 3) -and ($cat[0].price -gt 0)) $cat.Count
$h = Invoke-RestMethod "$base/api/tower/health"
Check "Santé : PostgreSQL, RabbitMQ, ERP OK" ($h.database -and $h.rabbitmq -and $h.erpAvailable) ""
$started = @($h.camelRoutes | Where-Object { $_.status -eq "Started" }).Count
Check "Toutes les routes Camel démarrées ($started)" ($started -eq @($h.camelRoutes).Count -and $started -ge 10) $started
Check "4 files RabbitMQ suivies" (@($h.queues.PSObject.Properties).Count -eq 4) ""

Write-Host "`n== Temps réel : WebSocket /ws/events pendant une commande"
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$ws.ConnectAsync([Uri]"ws://localhost:8080/ws/events", $cts.Token).Wait()
Check "Connexion WebSocket ouverte" ($ws.State -eq "Open") $ws.State

$order = Invoke-RestMethod -Method Post "$base/api/orders" -ContentType "application/json" `
    -Body '{"customerId":"CLI-001","shippingAddress":"Cocody, Abidjan","items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}'

$received = New-Object System.Collections.ArrayList
$buffer = New-Object byte[] 65536
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline -and -not ($received | Where-Object { $_.status -eq "DELIVERY_ASSIGNED" })) {
    $segment = New-Object System.ArraySegment[byte] -ArgumentList (, $buffer)
    $text = ""
    do {
        $task = $ws.ReceiveAsync($segment, $cts.Token)
        if (-not $task.Wait(3000)) { break }
        $text += [Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count)
    } while (-not $task.Result.EndOfMessage)
    if ($text) {
        $ev = $text | ConvertFrom-Json
        if ($ev.order_id -eq $order.orderId) { [void]$received.Add($ev) }
    }
}
$ws.Dispose()
$statuses = @($received | ForEach-Object { $_.status })
Write-Host "     reçus en direct : $($statuses -join ' > ')"
foreach ($s in @("RECEIVED", "PUBLISHED", "TRANSFORMED", "STOCK_LINE_CHECKED", "ROUTED", "INVOICED", "DISPATCHED", "DELIVERY_ASSIGNED")) {
    Check "Événement $s poussé en direct" ($statuses -contains $s) ""
}
$withPayload = @($received | Where-Object { $_.payload_format -eq "XML" })
Check "Le XML ERP est transmis aux écrans (inspecteur)" ($withPayload.Count -ge 1) ""

Write-Host "`n== Métriques calculées sur les vraies données"
$m = Invoke-RestMethod "$base/api/tower/metrics"
Check "1 commande comptée" ($m.total -eq 1) $m.total
Check "Taux de traitement 100 %" ($m.successRate -eq 100) $m.successRate
Check "Latence mesurée (> 0 ms)" ($m.avgLatencyMs -gt 0) $m.avgLatencyMs
Check "Événements journalisés comptés" ($m.events -ge $statuses.Count) $m.events

Write-Host "`n== Panne ERP vue par le Store"
Invoke-RestMethod -Method Post "$base/api/demo/erp/down" | Out-Null
$code = Http-Status { Invoke-RestMethod "$base/api/catalog" }
Check "Catalogue indisponible pendant la panne (503)" ($code -eq 503) $code
Invoke-RestMethod -Method Post "$base/api/demo/erp/up" | Out-Null
Check "Catalogue de retour après rétablissement" (@((Invoke-RestMethod "$base/api/catalog")).Count -eq 3) ""

Write-Host ""
if ($script:failures -eq 0) { Write-Host "Tous les tests de l'étape 6 passent." -ForegroundColor Green }
else { Write-Host "$($script:failures) test(s) en échec." -ForegroundColor Red; exit 1 }
