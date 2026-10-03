param([switch]$Foreground, [switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$runtimePath = Join-Path $appRoot '.runtime'
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
. (Join-Path $appRoot 'node-path.ps1')
. (Join-Path $appRoot 'certificate.ps1')
$nodePath = Get-CodexLightNode -Root $appRoot
$serverPath = Join-Path $appRoot 'server.mjs'
$runningPath = Join-Path $runtimePath 'running.json'
if ((Test-Path -LiteralPath $runningPath) -and -not $PrepareOnly) {
    $runInfo = Get-Content -LiteralPath $runningPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($runInfo.pid)" -ErrorAction SilentlyContinue
    if ($runningProcess -and $runningProcess.Name -eq 'node.exe' -and $runningProcess.CommandLine.Contains($serverPath)) {
        Write-Host "Codex LAN is running: http://127.0.0.1:8788"
        exit 0
    }
}
$interfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()
$ipAddresses = @($interfaces | ForEach-Object { $_.GetIPProperties().UnicastAddresses } | Where-Object {
    $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
    $_.Address.ToString() -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)'
} | ForEach-Object { $_.Address.ToString() } | Sort-Object -Unique)
Initialize-CodexLightCertificate -RuntimePath $runtimePath -Addresses $ipAddresses
$configPath = Join-Path $runtimePath 'config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = @{ callerThreadId = $env:CODEX_THREAD_ID; logRoot = (Join-Path $env:LOCALAPPDATA 'Codex\Logs') }
    [IO.File]::WriteAllText($configPath,($config|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if ($env:CODEX_APP_TOOLS_PIPE_PATH) {
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $config | Add-Member -NotePropertyName autoPipePath -NotePropertyValue $env:CODEX_APP_TOOLS_PIPE_PATH -Force
    [IO.File]::WriteAllText($configPath,($config|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if ($PrepareOnly) { return }
if ($Foreground) { & $nodePath $serverPath; exit $LASTEXITCODE }
$child = Start-Process -FilePath $nodePath -ArgumentList @("`"$serverPath`"") -WorkingDirectory $appRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'server.log') -RedirectStandardError (Join-Path $runtimePath 'server-error.log') -PassThru
Write-Host "Started Codex LAN (PID $($child.Id)). Open http://127.0.0.1:8788 on this computer."
Write-Host 'The phone address and pairing code are displayed in that panel.'
