param([ValidateRange(1,30)][int]$IntervalSeconds = 3)
$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$runtimePath = Join-Path $appRoot '.runtime'
$disabledPath = Join-Path $runtimePath 'maintenance-disabled'
$logPath = Join-Path $runtimePath 'maintenance.log'
. (Join-Path $appRoot 'node-path.ps1')
. (Join-Path $appRoot 'desktop-process.ps1')
$nodePath = Get-CodexLightNode -Root $appRoot
$installation = Join-Path $runtimePath 'keepalive.json'
if (Test-Path -LiteralPath $installation) {
    $record = Get-Content -LiteralPath $installation -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($record.nodePath -and (Test-Path -LiteralPath $record.nodePath)) { $nodePath = $record.nodePath }
}
$sha = [Security.Cryptography.SHA256]::Create()
try { $suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($appRoot.ToLowerInvariant())))).Replace('-','').Substring(0,12) }
finally { $sha.Dispose() }
$mutex = New-Object Threading.Mutex($false, ('Local\CodexLightMaintenance-' + $suffix))
$locked = $false
$processCache = @{}
function Write-MaintenanceLog([string]$Message) {
    Add-Content -LiteralPath $logPath -Value (([DateTime]::UtcNow.ToString('o')) + ' ' + $Message) -Encoding UTF8
}
function Get-OwnedProcess([string]$RecordName, [string]$ScriptName) {
    $recordFile = Join-Path $runtimePath $RecordName
    if (-not (Test-Path -LiteralPath $recordFile)) { return $null }
    try {
        $processRecord = Get-Content -LiteralPath $recordFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $processId = [int]$processRecord.pid
        if ($processId -le 0) { return $null }
        $cached = $processCache[$RecordName]
        if ($cached -and $cached.Id -eq $processId -and -not $cached.HasExited) { return $cached }
        if ($cached) { $cached.Dispose(); $processCache.Remove($RecordName) }
        $candidate = [Diagnostics.Process]::GetProcessById($processId)
        if ($candidate.HasExited -or $candidate.ProcessName -ne 'node') { $candidate.Dispose(); return $null }
        $details = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
        if (-not $details -and -not $candidate.HasExited) { $candidate.Dispose(); throw 'Process ownership could not be checked.' }
        $expected = Join-Path $appRoot $ScriptName
        $scriptPattern = '(?:^|\s)"?' + [regex]::Escape($expected) + '"?(?=\s|$)'
        if (-not $details.CommandLine -or $details.CommandLine -notmatch $scriptPattern) { $candidate.Dispose(); return $null }
        $processCache[$RecordName] = $candidate
        return $candidate
    } catch [ArgumentException] { return $null }
}
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked -or (Test-Path -LiteralPath $disabledPath)) { return }
    [IO.File]::WriteAllText((Join-Path $runtimePath 'maintainer.json'), (@{pid=$PID;startedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Write-MaintenanceLog 'monitor started'
    $lastState = ''
    $nextLaunch = [DateTime]::MinValue
    while (-not (Test-Path -LiteralPath $disabledPath)) {
        try {
            $watcher = Get-OwnedProcess 'watcher.json' 'watch.mjs'
            $server = Get-OwnedProcess 'running.json' 'server.mjs'
            $desktopRunning = Test-DesktopRunning
            $state = if ($watcher) { 'watcher running' } elseif ($server) { 'maintaining existing service' } elseif ($desktopRunning) { 'desktop ready; starting watcher' } else { 'waiting for desktop' }
            if ($state -ne $lastState) { Write-MaintenanceLog $state; $lastState = $state }
            if (-not $watcher -and -not $server -and $desktopRunning -and [DateTime]::UtcNow -ge $nextLaunch) {
                $child = Start-Process -FilePath $nodePath -ArgumentList @(('"' + (Join-Path $appRoot 'watch.mjs') + '"')) -WorkingDirectory $appRoot -WindowStyle Hidden -PassThru
                Write-MaintenanceLog ('started watcher pid=' + $child.Id)
                $child.Dispose()
                $nextLaunch = [DateTime]::UtcNow.AddSeconds(10)
            }
        } catch {
            Write-MaintenanceLog ('check failed: ' + $_.Exception.GetType().Name)
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
    Write-MaintenanceLog 'monitor disabled'
} finally {
    foreach ($cached in $processCache.Values) { $cached.Dispose() }
    if ($script:desktopProcess) { $script:desktopProcess.Dispose() }
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
