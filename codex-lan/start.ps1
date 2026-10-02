param([switch]$Foreground, [switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$runtimePath = Join-Path $appRoot '.runtime'
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
$nodePath = (Get-Command node -ErrorAction Stop).Source
$serverPath = Join-Path $appRoot 'server.mjs'
$runningPath = Join-Path $runtimePath 'running.json'
if ((Test-Path -LiteralPath $runningPath) -and -not $PrepareOnly) {
    $runInfo = Get-Content -LiteralPath $runningPath -Raw | ConvertFrom-Json
    $runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($runInfo.pid)" -ErrorAction SilentlyContinue
    if ($runningProcess -and $runningProcess.Name -eq 'node.exe' -and $runningProcess.CommandLine.Contains($serverPath)) {
        Write-Host "Codex LAN is running: http://127.0.0.1:8788"
        exit 0
    }
}
$opensslPath = (Get-Command openssl -ErrorAction SilentlyContinue).Source
if (-not $opensslPath) {
    $candidate = 'C:\ProgramData\miniforge3\Library\bin\openssl.exe'
    if (Test-Path -LiteralPath $candidate) { $opensslPath = $candidate }
}
if (-not $opensslPath) { throw 'OpenSSL is required to generate the local HTTPS certificate. Install OpenSSL and run again.' }
$certificatePath = Join-Path $runtimePath 'cert.pem'
$keyPath = Join-Path $runtimePath 'key.pem'
$interfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()
$ipAddresses = @($interfaces | ForEach-Object { $_.GetIPProperties().UnicastAddresses } | Where-Object {
    $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
    $_.Address.ToString() -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)'
} | ForEach-Object { $_.Address.ToString() } | Sort-Object -Unique)
$san = @('DNS:localhost','IP:127.0.0.1') + @($ipAddresses | ForEach-Object { "IP:$_" })
$sanText = $san -join ','
$sanPath = Join-Path $runtimePath 'certificate-addresses.txt'
$oldSan = if(Test-Path -LiteralPath $sanPath) { (Get-Content -LiteralPath $sanPath -Raw).Trim() } else { '' }
if (-not (Test-Path -LiteralPath $certificatePath) -or -not (Test-Path -LiteralPath $keyPath) -or $oldSan -ne $sanText) {
    $opensslConfigPath = Join-Path $runtimePath 'openssl.cnf'
    @('[req]','distinguished_name = dn','[dn]','CN = Codex LAN') | Set-Content -LiteralPath $opensslConfigPath -Encoding ascii
    & $opensslPath req -config $opensslConfigPath -x509 -newkey rsa:2048 -sha256 -days 365 -nodes -keyout $keyPath -out $certificatePath -subj '/CN=Codex LAN' -addext "subjectAltName=$sanText" -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign' -addext 'extendedKeyUsage=serverAuth' 2> (Join-Path $runtimePath 'certificate.log')
    if ($LASTEXITCODE -ne 0) { throw 'HTTPS certificate generation failed. See .runtime/certificate.log.' }
    Set-Content -LiteralPath $sanPath -Value $sanText -Encoding utf8
}
$configPath = Join-Path $runtimePath 'config.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = @{ callerThreadId = $env:CODEX_THREAD_ID; logRoot = (Join-Path $env:LOCALAPPDATA 'Codex\Logs') }
    [IO.File]::WriteAllText($configPath,($config|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if ($env:CODEX_APP_TOOLS_PIPE_PATH) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config | Add-Member -NotePropertyName autoPipePath -NotePropertyValue $env:CODEX_APP_TOOLS_PIPE_PATH -Force
    [IO.File]::WriteAllText($configPath,($config|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if ($PrepareOnly) { return }
if ($Foreground) { & $nodePath $serverPath; exit $LASTEXITCODE }
$child = Start-Process -FilePath $nodePath -ArgumentList @("`"$serverPath`"") -WorkingDirectory $appRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'server.log') -RedirectStandardError (Join-Path $runtimePath 'server-error.log') -PassThru
Write-Host "Started Codex LAN (PID $($child.Id)). Open http://127.0.0.1:8788 on this computer."
Write-Host 'The phone address and pairing code are displayed in that panel.'
