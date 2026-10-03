param([string]$Root=$PSScriptRoot, [string]$PluginHome=$env:USERPROFILE, [switch]$RegisterOnly)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$PluginHome=[IO.Path]::GetFullPath($PluginHome)
$destination=Join-Path $PluginHome '.codex\plugins\codex-light'
$catalogFile=Join-Path $PluginHome '.agents\plugins\marketplace.json'
$source=Join-Path $Root 'plugins\codex-light'
if(-not(Test-Path -LiteralPath (Join-Path $source 'plugin.json'))){throw 'The Codex Light plugin package is missing.'}
$catalog=$null
if(Test-Path -LiteralPath $catalogFile){
    $catalog=Get-Content -LiteralPath $catalogFile -Raw -Encoding UTF8|ConvertFrom-Json
    if(-not $catalog.name -or -not $catalog.PSObject.Properties['plugins']){throw 'The existing personal marketplace is invalid; it was preserved.'}
}else{$catalog=[pscustomobject]@{name='personal';interface=@{displayName='Personal'};plugins=@()}}
$entry=@($catalog.plugins|Where-Object{$_.name -eq 'codex-light'})
if($entry.Count -gt 1 -or ($entry.Count -eq 1 -and ($entry[0].source.source -ne 'local' -or $entry[0].source.path -ne './.codex/plugins/codex-light'))){throw 'An existing Codex Light marketplace entry points to another source; it was preserved.'}
if(Test-Path -LiteralPath $destination){
    if(-not(Test-Path -LiteralPath (Join-Path $destination '.codex-light-installation.json'))){throw 'An existing plugin folder was preserved because it has no Codex Light installation record.'}
}
New-Item -ItemType Directory -Path $destination -Force|Out-Null
foreach($file in Get-ChildItem -LiteralPath $source -File -Recurse -Force){
    $relative=$file.FullName.Substring($source.Length+1)
    $target=[IO.Path]::GetFullPath((Join-Path $destination $relative))
    if(-not $target.StartsWith($destination.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Plugin package path is outside its directory.'}
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
}
[IO.File]::WriteAllText((Join-Path $destination '.codex-light-installation.json'),(@{installRoot=[IO.Path]::GetFullPath($Root)}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$own=[pscustomobject]@{name='codex-light';source=@{source='local';path='./.codex/plugins/codex-light'};policy=@{installation='AVAILABLE';authentication='ON_INSTALL'};category='Productivity'}
$catalog.plugins=@($catalog.plugins|Where-Object{$_.name -ne 'codex-light'})+@($own)
New-Item -ItemType Directory -Path (Split-Path -Parent $catalogFile) -Force|Out-Null
[IO.File]::WriteAllText(($catalogFile+'.new'),($catalog|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath ($catalogFile+'.new') -Destination $catalogFile -Force
Write-Host "Plugin registered: codex-light@$($catalog.name)"
if($RegisterOnly){return}
$codexPath=$null
try {
    $desktop=Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1
    if($desktop){$candidate=Join-Path $desktop.InstallLocation 'app\resources\codex.exe';if(Test-Path -LiteralPath $candidate){$codexPath=$candidate}}
}catch{}
if(-not $codexPath){$command=Get-Command codex -ErrorAction SilentlyContinue;if($command){$codexPath=$command.Source}}
if(-not $codexPath){
    $binaryRoot=Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\bin'
    if(Test-Path -LiteralPath $binaryRoot){$binary=Get-ChildItem -LiteralPath $binaryRoot -Filter codex.exe -Recurse -File|Sort-Object LastWriteTime -Descending|Select-Object -First 1;if($binary){$codexPath=$binary.FullName}}
}
if(-not $codexPath){Write-Warning 'Open Codex Plugins, select Personal, and install Codex Light. Restart Codex first if the new source is not visible.';return}
& $codexPath plugin marketplace add $PluginHome
if($LASTEXITCODE -ne 0){Write-Warning 'The plugin is available in Personal, but Codex CLI could not load or update its configuration. Restart Codex and install Codex Light from Plugins. The connection service can still run.';return}
& $codexPath plugin add "codex-light@$($catalog.name)" --json
if($LASTEXITCODE -ne 0){Write-Warning 'Codex CLI could not complete plugin installation. Open Plugins and install Codex Light from the personal source. The connection service can still run.';return}
Write-Host 'Codex Light plugin installed. Start a new chat after restarting Codex to load its workflows.'
