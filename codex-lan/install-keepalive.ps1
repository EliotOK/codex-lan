$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
& (Join-Path $appRoot 'start.ps1') -PrepareOnly
. (Join-Path $appRoot 'node-path.ps1')
$nodePath = Get-CodexLightNode -Root $appRoot
$watchPath = Join-Path $appRoot 'watch.mjs'
$sha = [System.Security.Cryptography.SHA256]::Create()
$suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($appRoot.ToLowerInvariant())))).Replace('-','').Substring(0,12)
$taskName = "CodexLAN-$suffix"
$action = New-ScheduledTaskAction -Execute $nodePath -Argument "`"$watchPath`"" -WorkingDirectory $appRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval ([TimeSpan]::FromMinutes(1))
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and $existing.Actions.Arguments -ne "`"$watchPath`"") { throw '同名任务指向其他程序，未修改。' }
if (-not $existing) {
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Keep the local Codex LAN connection service running for this Windows user.' | Out-Null
} else {
    if ($existing.Actions.Execute -ne $nodePath) { Set-ScheduledTask -TaskName $taskName -Action $action | Out-Null }
    Enable-ScheduledTask -TaskName $taskName | Out-Null
}
Start-ScheduledTask -TaskName $taskName
[IO.File]::WriteAllText((Join-Path $appRoot '.runtime\keepalive.json'),(@{taskName=$taskName;watchPath=$watchPath;nodePath=$nodePath}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Host "Keepalive task enabled: $taskName. Desktop panel: http://127.0.0.1:8788"
