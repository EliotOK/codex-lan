$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
& (Join-Path $appRoot 'start.ps1') -PrepareOnly
. (Join-Path $appRoot 'node-path.ps1')
$nodePath = Get-CodexLightNode -Root $appRoot
$watchPath = Join-Path $appRoot 'watch.mjs'
$maintainPath = Join-Path $appRoot 'maintain.ps1'
$sha = [System.Security.Cryptography.SHA256]::Create()
$suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($appRoot.ToLowerInvariant())))).Replace('-','').Substring(0,12)
$taskName = "CodexLAN-$suffix"
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
$powershellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$maintenanceArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$maintainPath`""
if ($existing -and $existing.Actions.Arguments -ne "`"$watchPath`"" -and $existing.Actions.Arguments -ne $maintenanceArguments) { throw '同名任务指向其他程序，未修改。' }
$keepalivePath = Join-Path $appRoot '.runtime\keepalive.json'
if ($existing -and (Test-Path -LiteralPath $keepalivePath)) {
    $previous = Get-Content -LiteralPath $keepalivePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($previous.nodePath -and (Test-Path -LiteralPath $previous.nodePath)) {
        $version = & $previous.nodePath --version
        if ($LASTEXITCODE -eq 0 -and $version -match '^v(\d+)\.' -and [int]$Matches[1] -ge 22) { $nodePath = $previous.nodePath }
    }
}
$action = New-ScheduledTaskAction -Execute $powershellPath -Argument $maintenanceArguments -WorkingDirectory $appRoot
$loginTrigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
$recoveryTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval ([TimeSpan]::FromMinutes(1))
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval ([TimeSpan]::FromMinutes(1))
$definition = New-ScheduledTask -Action $action -Trigger @($loginTrigger,$recoveryTrigger) -Principal $principal -Settings $settings -Description 'Maintain Codex Light while Desktop is available; recover the monitor every minute.'
if ($existing) { Stop-ScheduledTask -TaskName $taskName }
Register-ScheduledTask -TaskName $taskName -InputObject $definition -Force | Out-Null
[IO.File]::WriteAllText($keepalivePath,(@{taskName=$taskName;watchPath=$watchPath;nodePath=$nodePath;maintainPath=$maintainPath;checkIntervalSeconds=3;recoveryIntervalSeconds=60}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$disabledPath = Join-Path $appRoot '.runtime\maintenance-disabled'
if (Test-Path -LiteralPath $disabledPath) { Remove-Item -LiteralPath $disabledPath }
Start-ScheduledTask -TaskName $taskName
Write-Host "Keepalive task enabled: $taskName. Desktop panel: http://127.0.0.1:8788"
