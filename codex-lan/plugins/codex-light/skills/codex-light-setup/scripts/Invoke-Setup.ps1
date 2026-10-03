param([ValidateSet('Install','Update','Repair','Status','Stop')][string]$Mode='Install', [string]$CallerThreadId, [switch]$NoFirewall, [switch]$NoBrowser)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$skillDirectory=Split-Path -Parent $PSScriptRoot
$installation=$null
$pluginDirectory=Split-Path -Parent (Split-Path -Parent $skillDirectory)
$marker=Join-Path $pluginDirectory '.codex-light-installation.json'
if(Test-Path -LiteralPath $marker){$installation=(Get-Content -LiteralPath $marker -Raw -Encoding UTF8|ConvertFrom-Json).installRoot}
if(-not $installation){$candidate=Join-Path $env:LOCALAPPDATA 'CodexLight';if(Test-Path -LiteralPath (Join-Path $candidate 'setup.ps1')){$installation=$candidate}}
if(-not $installation){$candidate=Split-Path -Parent (Split-Path -Parent $pluginDirectory);if(Test-Path -LiteralPath (Join-Path $candidate 'setup.ps1')){$installation=$candidate}}
if($Mode -eq 'Status'){
    if(-not $installation){Write-Host 'Codex Light is not installed.';return}
    Write-Host "Installation: $installation"
    try{$response=Invoke-WebRequest -Uri 'http://127.0.0.1:8788/' -UseBasicParsing -TimeoutSec 3;Write-Host "Desktop panel status: $($response.StatusCode)"}catch{Write-Host 'Desktop panel is unavailable.'}
    $file=Join-Path $installation '.runtime\keepalive.json'
    if(Test-Path -LiteralPath $file){$record=Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json;Get-ScheduledTask -TaskName $record.taskName|Select-Object TaskName,State}
    return
}
if($Mode -eq 'Stop'){
    if(-not $installation){Write-Host 'Codex Light is not installed.';return}
    & (Join-Path $installation 'stop.ps1');return
}
$source=$installation
if($Mode -eq 'Update' -or -not $source -or -not (Test-Path -LiteralPath (Join-Path $source 'runtime-bin\node.exe'))){
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    $headers=@{'User-Agent'='Codex-Light-Setup';'Accept'='application/vnd.github+json'}
    $release=Invoke-RestMethod -Uri 'https://api.github.com/repos/EliotOK/codex-lan/releases/latest' -Headers $headers -TimeoutSec 30
    $asset=@($release.assets|Where-Object{$_.name -eq 'codex-light-windows-x64.zip'})
    if($release.draft -or $release.prerelease -or $asset.Count -ne 1 -or $asset[0].digest -notmatch '^sha256:([a-f0-9]{64})$'){throw 'A verified Windows package is not available in the latest release.'}
    $expected=$Matches[1]
    $url=[Uri]$asset[0].browser_download_url
    if($url.Scheme -ne 'https' -or $url.Host -ne 'github.com' -or $url.AbsolutePath -notlike '/EliotOK/codex-lan/releases/download/*/codex-light-windows-x64.zip'){throw 'Unexpected Windows package download address.'}
    $download=Join-Path $env:LOCALAPPDATA ('CodexLightSetup\'+[Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $download -Force|Out-Null
    $archive=Join-Path $download 'desktop.zip'
    Invoke-WebRequest -Uri $url.AbsoluteUri -OutFile $archive -UseBasicParsing -TimeoutSec 120
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'Windows package SHA-256 mismatch.'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($archive)
    try{foreach($entry in $zip.Entries){$target=[IO.Path]::GetFullPath((Join-Path $download $entry.FullName));if(-not $target.StartsWith($download.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Archive entry is outside the extraction directory.'}}}finally{$zip.Dispose()}
    [IO.Compression.ZipFile]::ExtractToDirectory($archive,$download)
    $source=Join-Path $download 'codex-light-windows'
    if(-not (Test-Path -LiteralPath (Join-Path $source 'setup.ps1'))){throw 'The Windows package is incomplete.'}
}
$arguments=@{NoPlugin=$true}
if($installation){$arguments.InstallRoot=$installation}
if($CallerThreadId){$arguments.CallerThreadId=$CallerThreadId}
if($NoFirewall){$arguments.NoFirewall=$true}
if($NoBrowser){$arguments.NoBrowser=$true}
$powershell=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$nativeArguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $source 'setup.ps1'))
foreach($key in $arguments.Keys){$nativeArguments+=('-'+$key);if($arguments[$key] -isnot [bool]){$nativeArguments+=$arguments[$key]}}
& $powershell @nativeArguments
if($LASTEXITCODE -ne 0){throw 'Codex Light setup did not finish.'}
