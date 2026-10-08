param([string]$InstallRoot, [string]$CallerThreadId, [string]$PluginHome, [switch]$PrepareOnly, [switch]$NoFirewall, [switch]$NoPlugin, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $nativeModules=Join-Path $PSHOME 'Modules'
    $env:PSModulePath=$nativeModules+';'+(($env:PSModulePath.Split(';')|Where-Object{$_ -ne $nativeModules}) -join ';')
}
$sourceRoot = $PSScriptRoot
function Find-CodexLightInstallation {
    try {
        $listener = Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction Stop | Select-Object -First 1
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
        if ($process.Name -ne 'node.exe') { return $null }
        $match = [regex]::Match($process.CommandLine, '(?:"(?<script>[^"\r\n]+[\\/]server\.mjs)"|(?<script>[A-Za-z]:\\[^"\r\n]*?[\\/]server\.mjs)(?:\s|$))')
        if (-not $match.Success) { return $null }
        $candidate = Split-Path -Parent $match.Groups['script'].Value
        $record = Get-Content -LiteralPath (Join-Path $candidate '.runtime\running.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $package = Get-Content -LiteralPath (Join-Path $candidate 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($record.pid -eq $process.ProcessId -and $package.name -eq 'codex-lan') { return $candidate }
    } catch { }
    return $null
}
try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'The Windows package requires 64-bit Windows 10 or newer.' }
    if (-not $InstallRoot) { $InstallRoot = Find-CodexLightInstallation }
    if (-not $InstallRoot) { $InstallRoot = Join-Path $env:LOCALAPPDATA 'CodexLight' }
    $InstallRoot = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
    if ($InstallRoot.TrimEnd('\') -eq [IO.Path]::GetPathRoot($InstallRoot).TrimEnd('\')) { throw 'Choose a dedicated installation folder, not a drive root.' }
    if ((Test-Path -LiteralPath $InstallRoot) -and @(Get-ChildItem -LiteralPath $InstallRoot -Force).Count -gt 0) {
        $packageFile=Join-Path $InstallRoot 'package.json'
        if (-not (Test-Path -LiteralPath $packageFile) -or (Get-Content -LiteralPath $packageFile -Raw -Encoding UTF8 | ConvertFrom-Json).name -ne 'codex-lan') { throw 'The selected folder contains another program. Choose an empty folder or an existing Codex Light installation.' }
    }
    if ($CallerThreadId -and $CallerThreadId -notmatch '^[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$') { throw 'The connection chat ID is invalid.' }
    $sourceNode = Join-Path $sourceRoot 'runtime-bin\node.exe'
    $nodeMetadata = Get-Content -LiteralPath (Join-Path $sourceRoot 'node-runtime.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not (Test-Path -LiteralPath $sourceNode)) { throw 'Use codex-light-windows-x64.zip for double-click installation; the source archive does not include Node.js.' }
    if ((Get-FileHash -LiteralPath $sourceNode -Algorithm SHA256).Hash.ToLowerInvariant() -ne $nodeMetadata.sha256) { throw 'Bundled Node.js checksum mismatch. Download the package again.' }
    if (-not $PrepareOnly) {
        $serverPath=Join-Path $InstallRoot 'server.mjs'
        foreach ($listener in @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in 8787,8788})) {
            $owner=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction Stop
            if ($owner.Name -ne 'node.exe' -or -not $owner.CommandLine.Contains($serverPath)) { throw 'Ports 8787/8788 belong to another installation or program. Stop that service or choose its verified installation folder.' }
        }
    }
    New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
    $existingTask = $null
    $keepaliveFile = Join-Path $InstallRoot '.runtime\keepalive.json'
    if (-not $PrepareOnly -and (Test-Path -LiteralPath $keepaliveFile)) {
        $keepalive = Get-Content -LiteralPath $keepaliveFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $existingTask = Get-ScheduledTask -TaskName $keepalive.taskName -ErrorAction SilentlyContinue
        if ($existingTask) {
            $watchPath = Join-Path $InstallRoot 'watch.mjs'
            $maintenanceArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$(Join-Path $InstallRoot 'maintain.ps1')`""
            if ($existingTask.Actions.Arguments -ne "`"$watchPath`"" -and $existingTask.Actions.Arguments -ne $maintenanceArguments) { throw 'The existing task belongs to another program.' }
            Stop-ScheduledTask -TaskName $keepalive.taskName
        }
    }
    if (-not $PrepareOnly -and (Test-Path -LiteralPath (Join-Path $InstallRoot 'stop.ps1'))) { & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -ExecutionPolicy Bypass -File (Join-Path $InstallRoot 'stop.ps1'); if ($LASTEXITCODE -ne 0) { throw 'Could not stop the existing service.' } }
    if ($sourceRoot -ne $InstallRoot) {
        $topFiles = Get-ChildItem -LiteralPath $sourceRoot -File | Where-Object { $_.Extension -in '.mjs','.ps1','.bat','.md','.json' -or $_.Name -eq 'LICENSE' }
        foreach ($file in $topFiles) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $InstallRoot $file.Name) -Force }
        foreach ($directory in @('public','plugins','runtime-bin')) {
            $source = Join-Path $sourceRoot $directory
            foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse -Force) {
                $relative = $file.FullName.Substring($sourceRoot.Length + 1)
                $target = [IO.Path]::GetFullPath((Join-Path $InstallRoot $relative))
                if (-not $target.StartsWith($InstallRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Package path is outside the installation directory.' }
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
                Copy-Item -LiteralPath $file.FullName -Destination $target -Force
            }
        }
    }
    & (Join-Path $InstallRoot 'start.ps1') -PrepareOnly
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Service preparation failed.' }
    . (Join-Path $InstallRoot 'node-path.ps1')
    $nodePath = Get-CodexLightNode -Root $InstallRoot
    if ($CallerThreadId) { & $nodePath --disable-warning=ExperimentalWarning (Join-Path $InstallRoot 'configure-connection.mjs') $CallerThreadId }
    else { & $nodePath --disable-warning=ExperimentalWarning (Join-Path $InstallRoot 'configure-connection.mjs') }
    if ($LASTEXITCODE -ne 0) { throw 'Connection preparation failed.' }
    if (-not $NoPlugin) {
        $pluginArguments=@{Root=$InstallRoot;RegisterOnly=[bool]$PrepareOnly}
        if ($PluginHome) { $pluginArguments.PluginHome=$PluginHome }
        & (Join-Path $InstallRoot 'install-plugin.ps1') @pluginArguments
    }
    if ($PrepareOnly) { Write-Host "Prepared: $InstallRoot"; return }
    & (Join-Path $InstallRoot 'install-keepalive.ps1')
    if (-not $NoFirewall) {
        $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        try {
            if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { & (Join-Path $InstallRoot 'firewall.ps1') -Root $InstallRoot }
            else {
                $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
                $firewallScript = Join-Path $InstallRoot 'firewall.ps1'
                $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$firewallScript`" -Root `"$InstallRoot`""
                $elevated = Start-Process -FilePath $powershell -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
                if ($elevated.ExitCode -ne 0) { throw 'Firewall configuration did not finish.' }
            }
        } catch { Write-Warning 'Service installed, but Windows firewall permission was not granted. Run install.bat again to retry this step.' }
    }
    $ready = $false
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        try {
            $null = Invoke-WebRequest -Uri 'http://127.0.0.1:8788/' -UseBasicParsing -TimeoutSec 2
            $run = Get-Content -LiteralPath (Join-Path $InstallRoot '.runtime\running.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($run.pid)"
            $listener = Get-NetTCPConnection -LocalPort 8787 -State Listen | Select-Object -First 1
            if ($process.Name -eq 'node.exe' -and $process.CommandLine.Contains((Join-Path $InstallRoot 'server.mjs')) -and $listener.OwningProcess -eq $run.pid) { $ready=$true; break }
            Start-Sleep -Milliseconds 500
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'The desktop panel did not start. Check .runtime/server-error.log and whether ports 8787/8788 are already occupied.' }
    Write-Host "Installed: $InstallRoot"
    Write-Host 'Desktop panel: http://127.0.0.1:8788/'
    Write-Host 'Public phone certificate: .runtime\cert.pem'
    if (-not $NoBrowser) { Start-Process -FilePath 'http://127.0.0.1:8788/' -WindowStyle Hidden }
} catch {
    if ($existingTask -and $existingTask.Settings.Enabled) { try {
        $disabledPath = Join-Path $InstallRoot '.runtime\maintenance-disabled'
        if (Test-Path -LiteralPath $disabledPath) { Remove-Item -LiteralPath $disabledPath }
        Enable-ScheduledTask -TaskName $existingTask.TaskName | Out-Null
        Start-ScheduledTask -TaskName $existingTask.TaskName
    } catch { } }
    Write-Error $_
    exit 1
}
