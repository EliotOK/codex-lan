$ErrorActionPreference = 'Stop'
$runtimePath = Join-Path $PSScriptRoot '.runtime'
if (Test-Path -LiteralPath $runtimePath) { [IO.File]::WriteAllText((Join-Path $runtimePath 'maintenance-disabled'), [DateTime]::UtcNow.ToString('o')) }
$keepalivePath = Join-Path $PSScriptRoot '.runtime\keepalive.json'
if(Test-Path -LiteralPath $keepalivePath) {
    $keepalive = Get-Content -LiteralPath $keepalivePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $task = Get-ScheduledTask -TaskName $keepalive.taskName -ErrorAction SilentlyContinue
    $maintenanceArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$(Join-Path $PSScriptRoot 'maintain.ps1')`""
    if($task -and ($task.Actions.Arguments -eq "`"$(Join-Path $PSScriptRoot 'watch.mjs')`"" -or $task.Actions.Arguments -eq $maintenanceArguments)) {
        Disable-ScheduledTask -TaskName $keepalive.taskName | Out-Null
        Stop-ScheduledTask -TaskName $keepalive.taskName
    }
}
foreach ($entry in @(@{Record='maintainer.json';Script='maintain.ps1';Name='powershell.exe'},@{Record='watcher.json';Script='watch.mjs';Name='node.exe'})) {
    $recordPath = Join-Path $runtimePath $entry.Record
    if (-not (Test-Path -LiteralPath $recordPath)) { continue }
    try {
        $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$record.pid)" -ErrorAction Stop
        $scriptPattern = '(?:^|\s)"?' + [regex]::Escape((Join-Path $PSScriptRoot $entry.Script)) + '"?(?=\s|$)'
        if ($owned -and $owned.Name -eq $entry.Name -and $owned.CommandLine -match $scriptPattern) {
            Stop-Process -Id $owned.ProcessId -ErrorAction SilentlyContinue
            Wait-Process -Id $owned.ProcessId -Timeout 5 -ErrorAction SilentlyContinue
        }
    } catch { }
}
$runningPath = Join-Path $PSScriptRoot '.runtime\running.json'
if (-not (Test-Path -LiteralPath $runningPath)) { Write-Host 'No running service record.'; exit 0 }
$runInfo = Get-Content -LiteralPath $runningPath -Raw -Encoding UTF8 | ConvertFrom-Json
$runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($runInfo.pid)" -ErrorAction SilentlyContinue
$serverPath = Join-Path $PSScriptRoot 'server.mjs'
$serverPattern = '(?:^|\s)"?' + [regex]::Escape($serverPath) + '"?(?=\s|$)'
if ($runningProcess -and $runningProcess.Name -eq 'node.exe' -and $runningProcess.CommandLine -match $serverPattern) {
    Stop-Process -Id $runInfo.pid
    Wait-Process -Id $runInfo.pid -Timeout 5 -ErrorAction SilentlyContinue
    Write-Host 'Codex LAN stopped.'
} else { Write-Host 'The recorded service is no longer running.' }
