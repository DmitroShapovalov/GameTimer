<#
.SYNOPSIS
  End-to-end check of the phone remote on a real PC: pairs, opens /ws, drives the timer and
  settings through the API and checks that each WebSocket event arrives within 1 s.
  Works in Windows PowerShell 5.1 and PowerShell 7.
.DESCRIPTION
  Safe to run: switches the expiry action to "lock" for the test, stops the timer and restores
  the settings at the end (also on errors), and removes its own paired device.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\ws-selftest.ps1 -Code ABCD2345
#>
param(
    [Parameter(Mandatory)] [string]$Code,
    [string]$Server = "localhost:5123"
)
$ErrorActionPreference = "Stop"
$base = "http://$Server"
$none = [Threading.CancellationToken]::None
$script:fails = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if (-not $ok) { $script:fails++ }
    $mark = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host ("{0}  {1}  {2}" -f $mark, $name, $detail) -ForegroundColor $color
}

function Api([string]$method, [string]$path, $body = $null) {
    $params = @{ Method = $method; Uri = "$base$path"; Headers = $script:auth }
    if ($null -ne $body) { $params.ContentType = "application/json"; $params.Body = ($body | ConvertTo-Json -Depth 5 -Compress) }
    Invoke-RestMethod @params
}

# One receive is always pending; Next waits for it with a timeout and returns the parsed message
$buffer = New-Object byte[] 65536
$script:pending = $null
function Next([int]$timeoutMs = 1500) {
    if ($null -eq $script:pending) {
        $script:pending = $script:ws.ReceiveAsync([ArraySegment[byte]]$buffer, $none)
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    if (-not $script:pending.Wait($timeoutMs)) { return $null }
    $result = $script:pending.Result
    $script:pending = $null
    if ($result.MessageType -eq "Close") {
        return [pscustomobject]@{ type = "(closed: $($script:ws.CloseStatus) $($script:ws.CloseStatusDescription))"; ms = $sw.ElapsedMilliseconds }
    }
    $msg = [Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count) | ConvertFrom-Json
    $msg | Add-Member -NotePropertyName ms -NotePropertyValue $sw.ElapsedMilliseconds
    return $msg
}

function Expect([string]$name, [string]$type, [scriptblock]$action, [int]$timeoutMs = 1500) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $action | Out-Null
    $msg = Next $timeoutMs
    $ms = $sw.ElapsedMilliseconds
    if ($null -eq $msg) { Check $name $false "no message within $timeoutMs ms (expected '$type')"; return $null }
    Check $name ($msg.type -eq $type) ("got '{0}' after {1} ms" -f $msg.type, $ms)
    return $msg
}

Write-Host "PowerShell $($PSVersionTable.PSVersion), .NET $([Environment]::Version)"

# --- pair
$pair = Invoke-RestMethod -Method Post "$base/pair" -ContentType application/json -Body (@{ code = $Code; name = "selftest" } | ConvertTo-Json)
$script:auth = @{ Authorization = "Bearer $($pair.token)" }
Check "pair" ($null -ne $pair.token) "device $($pair.deviceId)"

$original = Api GET /api/settings
try {
    Api PUT /api/settings @{ action = "lock" } | Out-Null
    $status = Api GET /api/status
    if ($status.state -eq "running") { Api POST /api/timer/stop | Out-Null }

    # --- connect
    $script:ws = New-Object System.Net.WebSockets.ClientWebSocket
    $script:ws.Options.SetRequestHeader("Authorization", "Bearer $($pair.token)")
    [void]$script:ws.ConnectAsync([Uri]"ws://$Server/ws", $none).GetAwaiter().GetResult()
    $hello = Next 3000
    Check "hello after connect" ($hello.type -eq "hello") "state=$($hello.status.state)"

    # --- events from API calls
    $m = Expect "API start -> started" "started" { Api POST /api/timer/start @{ mode = "interval"; hours = 0; minutes = 30 } }
    # 30 -> 15 min: avoids the default alert points (10 / 2 / 1 min)
    $m = Expect "API extend -> extended" "extended" { Api POST /api/timer/extend @{ minutes = -15 } }
    if ($m) { Check "extended carries minutes and status" ($m.minutes -eq -15 -and $m.status.remainingSeconds -le 900) "minutes=$($m.minutes) remaining=$($m.status.remainingSeconds)" }

    Write-Host "Waiting up to 7 s for a tick..."
    do { $m = Next 7000 } while ($m -and $m.type -eq "alert") # custom alert points may fire
    Check "tick while running" ($m.type -eq "tick") ("got '{0}'" -f $m.type)
    $m = Next 7000
    Check "next tick ~5 s later" ($m.type -eq "tick" -and $m.ms -gt 4000 -and $m.ms -lt 6500) ("got '{0}' after {1} ms" -f $m.type, $m.ms)

    $m = Expect "API settings change -> settingsChanged" "settingsChanged" { Api PUT /api/settings @{ yellowThreshold = ($original.yellowThreshold % 1000) + 1 } }
    $m = Expect "API stop -> stopped" "stopped" { Api POST /api/timer/stop }

    # --- events from the PC window
    Write-Host ""
    Write-Host "Now press START in the GameTimer window (you have 20 s)..." -ForegroundColor Yellow
    $m = Next 20000
    Check "PC start -> started" ($m.type -eq "started") ("got '{0}'" -f $m.type)
    if ($m.type -eq "started") {
        Write-Host "Now press STOP in the GameTimer window (confirm Windows Hello; you have 30 s)..." -ForegroundColor Yellow
        do { $m = Next 30000 } while ($m -and $m.type -eq "tick")
        Check "PC stop -> stopped" ($m.type -eq "stopped") ("got '{0}'" -f $m.type)
    }

    # --- restore the settings we changed (also checks a second settingsChanged)
    $m = Expect "restore settings -> settingsChanged" "settingsChanged" { Api PUT /api/settings @{ action = $original.action; yellowThreshold = $original.yellowThreshold } }
    $script:restored = $true

    # --- revoking ourselves closes the socket
    $m = Expect "revoke own device -> socket closed 'revoked'" "(closed: PolicyViolation revoked)" { Api DELETE "/api/devices/$($pair.deviceId)" }
    $script:revoked = $true
}
finally {
    if (-not $script:revoked) {
        # a step failed: clean up with our token while we still have it
        try { $s = Api GET /api/status; if ($s.state -eq "running") { Api POST /api/timer/stop | Out-Null } } catch { }
        if (-not $script:restored) {
            try { Api PUT /api/settings @{ action = $original.action; yellowThreshold = $original.yellowThreshold } | Out-Null } catch { }
        }
        try { Api DELETE "/api/devices/$($pair.deviceId)" | Out-Null } catch { }
    }
    if ($script:ws) { $script:ws.Dispose() }
}
Write-Host ""
if ($script:fails -eq 0) { Write-Host "ALL PASSED" -ForegroundColor Green } else { Write-Host "$($script:fails) FAILED" -ForegroundColor Red }
