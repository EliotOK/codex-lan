function Test-CodexDesktopPath {
    param([string]$Executable, [string]$LocalAppData = $env:LOCALAPPDATA)
    if (-not $Executable) { return $false }
    if ($Executable -match '\\WindowsApps\\OpenAI\.Codex_[^\\]+\\app\\(?:Codex|ChatGPT)\.exe$') { return $true }
    foreach ($relative in @('Programs\Codex\Codex.exe','OpenAI\Codex\app\Codex.exe','OpenAI\Codex\app\ChatGPT.exe')) {
        if ($Executable.Equals((Join-Path $LocalAppData $relative), [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}
function Test-DesktopRunning {
    if ($script:desktopProcess -and -not $script:desktopProcess.HasExited) { return $true }
    if ($script:desktopProcess) { $script:desktopProcess.Dispose(); $script:desktopProcess = $null }
    $candidates = @([Diagnostics.Process]::GetProcessesByName('codex')) + @([Diagnostics.Process]::GetProcessesByName('ChatGPT'))
    try {
        foreach ($candidate in $candidates) {
            try {
                if (-not (Test-CodexDesktopPath $candidate.MainModule.FileName)) { continue }
                if ($candidate.MainWindowHandle -eq [IntPtr]::Zero) {
                    $details = Get-CimInstance Win32_Process -Filter "ProcessId=$($candidate.Id)" -ErrorAction Stop
                    if (-not $details.CommandLine -or $details.CommandLine -match '(?:^|\s)--type(?:=|\s)') { continue }
                }
                if (-not $candidate.HasExited) { $script:desktopProcess = $candidate; return $true }
            } catch { }
        }
        return $false
    } finally {
        foreach ($candidate in $candidates) { if ($candidate -ne $script:desktopProcess) { $candidate.Dispose() } }
    }
}
