function Write-CodexLightPem {
    param([string]$Path, [string]$Label, [byte[]]$Bytes)
    $base64 = [Convert]::ToBase64String($Bytes)
    $lines = for ($offset = 0; $offset -lt $base64.Length; $offset += 64) { $base64.Substring($offset, [Math]::Min(64, $base64.Length - $offset)) }
    [IO.File]::WriteAllText($Path, "-----BEGIN $Label-----`n" + ($lines -join "`n") + "`n-----END $Label-----`n", [Text.Encoding]::ASCII)
}
function Initialize-CodexLightCertificate {
    param([string]$RuntimePath, [string[]]$Addresses)
    New-Item -ItemType Directory -Path $RuntimePath -Force | Out-Null
    $certPath = Join-Path $RuntimePath 'cert.pem'
    $keyPath = Join-Path $RuntimePath 'key.pem'
    $sanPath = Join-Path $RuntimePath 'certificate-addresses.txt'
    $ips = @($Addresses | Sort-Object -Unique)
    $sanText = (@('DNS:localhost', 'IP:127.0.0.1') + @($ips | ForEach-Object { "IP:$_" })) -join ','
    $oldSan = if (Test-Path -LiteralPath $sanPath) { (Get-Content -LiteralPath $sanPath -Raw -Encoding UTF8).Trim() } else { '' }
    if ((Test-Path -LiteralPath $certPath) -and (Test-Path -LiteralPath $keyPath) -and $oldSan -eq $sanText) {
        $encoded = (Get-Content -LiteralPath $certPath -Raw) -replace '-----[\w ]+-----', '' -replace '\s', ''
        $existing = New-Object Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList @(,[Convert]::FromBase64String($encoded))
        try { if ($existing.NotAfter -gt [DateTime]::Now.AddDays(7)) { return } } finally { $existing.Dispose() }
    }
    $certificate = $null; $rsa = $null
    $certTemp = Join-Path $RuntimePath 'cert.pem.new'; $keyTemp = Join-Path $RuntimePath 'key.pem.new'
    try {
        $extensions = @('2.5.29.19={critical}{text}ca=true', '2.5.29.37={text}1.3.6.1.5.5.7.3.1')
        $san = (@('DNS=localhost', 'IPAddress=127.0.0.1') + @($ips | ForEach-Object { "IPAddress=$_" })) -join '&'
        $extensions += "2.5.29.17={text}$san"
        $certificate = New-SelfSignedCertificate -Type Custom -Subject 'CN=Codex Light' -Provider 'Microsoft Software Key Storage Provider' -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -CertStoreLocation 'Cert:\CurrentUser\My' -KeyUsage DigitalSignature,KeyEncipherment,CertSign -TextExtension $extensions -NotAfter ([DateTime]::Now.AddYears(1))
        $rsa = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($certificate)
        if ($rsa -isnot [Security.Cryptography.RSACng]) { throw 'Windows CNG RSA export is unavailable.' }
        Write-CodexLightPem -Path $keyTemp -Label 'PRIVATE KEY' -Bytes $rsa.Key.Export([Security.Cryptography.CngKeyBlobFormat]::Pkcs8PrivateBlob)
        Write-CodexLightPem -Path $certTemp -Label 'CERTIFICATE' -Bytes $certificate.RawData
        Move-Item -LiteralPath $keyTemp -Destination $keyPath -Force
        Move-Item -LiteralPath $certTemp -Destination $certPath -Force
        [IO.File]::WriteAllText($sanPath, $sanText, [Text.UTF8Encoding]::new($false))
    } finally {
        if ($rsa) { $rsa.Dispose() }
        if ($certificate) {
            $temporaryThumbprint = $certificate.Thumbprint
            $certificate.Dispose()
            Remove-Item -LiteralPath "Cert:\CurrentUser\My\$temporaryThumbprint" -DeleteKey -Force -ErrorAction SilentlyContinue
        }
        foreach ($file in @($certTemp, $keyTemp)) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force } }
    }
}
