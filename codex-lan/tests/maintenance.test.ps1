$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\desktop-process.ps1')
$appData = 'C:\Users\fixture\AppData\Local'
$cases = @(
    @{Path='C:\Program Files\WindowsApps\OpenAI.Codex_26.1002.7124.0_x64__publisher\app\ChatGPT.exe';Expected=$true},
    @{Path='C:\Program Files\WindowsApps\OpenAI.Codex_26.928.3736.0_x64__publisher\app\Codex.exe';Expected=$true},
    @{Path='C:\Users\fixture\AppData\Local\Programs\Codex\Codex.exe';Expected=$true},
    @{Path='C:\Users\fixture\AppData\Local\OpenAI\Codex\bin\hash\codex.exe';Expected=$false},
    @{Path='C:\Program Files\WindowsApps\OpenAI.ChatGPT_1.0_x64__publisher\app\ChatGPT.exe';Expected=$false},
    @{Path='C:\Other\Codex.exe';Expected=$false},
    @{Path='';Expected=$false}
)
foreach ($case in $cases) {
    if ((Test-CodexDesktopPath -Executable $case.Path -LocalAppData $appData) -ne $case.Expected) { throw ('Unexpected Desktop identity: ' + $case.Path) }
}
$scriptRoot = Split-Path -Parent $PSScriptRoot
foreach ($name in @('desktop-process.ps1','maintain.ps1','install-keepalive.ps1','stop.ps1','setup.ps1')) {
    $tokens=$null; $errors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $scriptRoot $name),[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw ($errors.Message -join '; ') }
}
Write-Output 'Seven Desktop identity cases and five PowerShell syntax checks passed.'
