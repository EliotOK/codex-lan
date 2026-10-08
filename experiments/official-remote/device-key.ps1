$ErrorActionPreference = 'Stop'
$key = $null
$signer = $null
try {
    $request = [Console]::In.ReadLine() | ConvertFrom-Json
    if ($request.operation -eq 'create') {
        $keyName = 'codex-light-remote-probe-' + [Guid]::NewGuid().ToString()
        $parameters = [System.Security.Cryptography.CngKeyCreationParameters]::new()
        $parameters.Provider = [System.Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider
        $parameters.ExportPolicy = [System.Security.Cryptography.CngExportPolicies]::None
        $parameters.KeyUsage = [System.Security.Cryptography.CngKeyUsages]::Signing
        $key = [System.Security.Cryptography.CngKey]::Create([System.Security.Cryptography.CngAlgorithm]::ECDsaP256, $keyName, $parameters)
        if ($key.ExportPolicy -ne [System.Security.Cryptography.CngExportPolicies]::None) {
            $key.Delete()
            throw 'Key protection failed'
        }
        $signer = [System.Security.Cryptography.ECDsaCng]::new($key)
        $result = @{ keyId = $keyName; algorithm = 'ecdsa_p256_sha256'; protectionClass = 'os_protected_nonextractable'; publicKeySpkiDerBase64 = [Convert]::ToBase64String($signer.ExportSubjectPublicKeyInfo()); nonextractable = $true }
    } else {
        if ($request.keyId -notmatch '^codex-light-remote-probe-[a-f0-9-]{36}$') { throw 'Invalid probe key identifier' }
        $key = [System.Security.Cryptography.CngKey]::Open($request.keyId, [System.Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider)
        switch ($request.operation) {
            'read-public' {
                if ($key.ExportPolicy -ne [System.Security.Cryptography.CngExportPolicies]::None -or $key.Algorithm -ne [System.Security.Cryptography.CngAlgorithm]::ECDsaP256) { throw 'Unexpected key protection or algorithm' }
                $signer = [System.Security.Cryptography.ECDsaCng]::new($key)
                $result = @{ keyId = $request.keyId; algorithm = 'ecdsa_p256_sha256'; protectionClass = 'os_protected_nonextractable'; publicKeySpkiDerBase64 = [Convert]::ToBase64String($signer.ExportSubjectPublicKeyInfo()); nonextractable = $true }
            }
            'sign' {
                $bytes = [Convert]::FromBase64String($request.payloadBase64)
                if ($bytes.Length -gt 32768) { throw 'Payload too large' }
                $payload = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
                if ($payload.domain -ne 'codex-device-key-sign-payload/v1' -or $payload.payload.targetOrigin -ne 'https://chatgpt.com' -or $payload.payload.type -notin @('remoteControlClientEnrollment', 'remoteControlClientConnection')) { throw 'Invalid signing context' }
                $signer = [System.Security.Cryptography.ECDsaCng]::new($key)
                $signature = $signer.SignData($bytes, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence)
                $result = @{ algorithm = 'ecdsa_p256_sha256'; signatureDerBase64 = [Convert]::ToBase64String($signature) }
            }
            'delete' { $key.Delete(); $result = @{ deleted = $true } }
            default { throw 'Unknown key operation' }
        }
    }
    [Console]::Out.WriteLine(($result | ConvertTo-Json -Compress))
} catch {
    [Console]::Out.WriteLine('{"error":"device-key-operation-failed"}')
    exit 1
} finally {
    if ($null -ne $signer) { $signer.Dispose() }
    if ($null -ne $key) { $key.Dispose() }
}
