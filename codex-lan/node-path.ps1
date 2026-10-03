function Get-CodexLightNode {
    param([string]$Root = $PSScriptRoot)
    $bundled = Join-Path $Root 'runtime-bin\node.exe'
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    $command = Get-Command node -ErrorAction SilentlyContinue
    if (-not $command) { throw 'Download codex-light-windows-x64.zip, which includes Node.js.' }
    $version = & $command.Source --version
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) { throw 'Node.js 22 or newer is required. Use the Windows package.' }
    return $command.Source
}
