param([Parameter(Mandatory=$true)][string]$Root, [switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$Root = [IO.Path]::GetFullPath($Root)
$program = Join-Path $Root 'runtime-bin\node.exe'
if (-not (Test-Path -LiteralPath $program -PathType Leaf)) { throw 'Bundled Node.js is missing.' }
$hash = [Security.Cryptography.SHA256]::Create()
try { $suffix = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Root.ToLowerInvariant())))).Replace('-','').Substring(0,12) } finally { $hash.Dispose() }
$ruleName = "CodexLight-$suffix"
$plan = @{Name=$ruleName;Program=$program;Direction='Inbound';Action='Allow';Profile='Private';Protocol='TCP';LocalPort=8787;RemoteAddress='LocalSubnet'}
if ($PlanOnly) { $plan | ConvertTo-Json; exit 0 }
$existing = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    $filter = $existing | Get-NetFirewallApplicationFilter
    if ($filter.Program -ne $program) { throw 'An existing firewall rule points to a different program; it was not changed.' }
    Remove-NetFirewallRule -Name $ruleName
}
New-NetFirewallRule -Name $ruleName -DisplayName 'Codex Light (private LAN)' -Direction Inbound -Action Allow -Enabled True -Profile Private -Program $program -Protocol TCP -LocalPort 8787 -RemoteAddress LocalSubnet | Out-Null
