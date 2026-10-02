$ErrorActionPreference = 'Stop'
$keepalivePath = Join-Path $PSScriptRoot '.runtime\keepalive.json'
if(Test-Path -LiteralPath $keepalivePath) {
    $keepalive = Get-Content -LiteralPath $keepalivePath -Raw | ConvertFrom-Json
    $task = Get-ScheduledTask -TaskName $keepalive.taskName -ErrorAction SilentlyContinue
    if($task -and $task.Actions.Arguments -eq "`"$(Join-Path $PSScriptRoot 'watch.mjs')`"") {
        Disable-ScheduledTask -TaskName $keepalive.taskName | Out-Null
        Stop-ScheduledTask -TaskName $keepalive.taskName
    }
}
$runningPath = Join-Path $PSScriptRoot '.runtime\running.json'
if (-not (Test-Path -LiteralPath $runningPath)) { Write-Host 'No running service record.'; exit 0 }
$runInfo = Get-Content -LiteralPath $runningPath -Raw | ConvertFrom-Json
$runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($runInfo.pid)" -ErrorAction SilentlyContinue
$serverPath = Join-Path $PSScriptRoot 'server.mjs'
if ($runningProcess -and $runningProcess.Name -eq 'node.exe' -and $runningProcess.CommandLine.Contains($serverPath)) {
    Stop-Process -Id $runInfo.pid
    Write-Host 'Codex LAN stopped.'
} else { Write-Host 'The recorded service is no longer running.' }
