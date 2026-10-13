<#
.SYNOPSIS
  Prints GameTimer WebSocket events (/ws) with timestamps. For testing the phone remote.
.EXAMPLE
  .\ws-watch.ps1 -Code ABCD2345      # pair with the code from "Connect phone", then watch
.EXAMPLE
  .\ws-watch.ps1 -Token <token>      # watch with an existing device token
#>
param(
    [string]$Code,
    [string]$Token,
    [string]$Server = "localhost:5123"
)
$ErrorActionPreference = "Stop"

if ($Code) {
    $body = @{ code = $Code; name = "ws-watch" } | ConvertTo-Json
    $Token = (Invoke-RestMethod -Method Post "http://$Server/pair" -ContentType application/json -Body $body).token
    Write-Host "Paired as 'ws-watch'. Token (reuse with -Token): $Token" -ForegroundColor Green
}
if (-not $Token) { throw "Pass -Code (from 'Connect phone') or -Token." }

$none = [Threading.CancellationToken]::None
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.Options.SetRequestHeader("Authorization", "Bearer $Token")
[void]$ws.ConnectAsync([Uri]"ws://$Server/ws", $none).GetAwaiter().GetResult()
Write-Host "Connected. Waiting for events (Ctrl+C to quit)..." -ForegroundColor Green

$buffer = New-Object byte[] 65536
try {
    while ($ws.State -eq "Open") {
        $result = $ws.ReceiveAsync([ArraySegment[byte]]$buffer, $none).GetAwaiter().GetResult()
        if ($result.MessageType -eq "Close") {
            Write-Host "Closed by server: $($ws.CloseStatus) $($ws.CloseStatusDescription)" -ForegroundColor Yellow
            [void]$ws.CloseOutputAsync("NormalClosure", "", $none).GetAwaiter().GetResult()
            break
        }
        "{0:HH:mm:ss.fff}  {1}" -f (Get-Date), [Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
    }
}
finally {
    $ws.Dispose()
}
