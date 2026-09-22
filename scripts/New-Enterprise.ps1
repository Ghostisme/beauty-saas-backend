# Platform operator only. Passwords are requested interactively and never printed or stored.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{2,39}$')]
    [string] $Code,
    [Parameter(Mandatory = $true)] [ValidateLength(1, 100)] [string] $Name,
    [Parameter(Mandatory = $true)] [ValidateLength(1, 50)] [string] $AdminName,
    [ValidateLength(0, 20)] [string] $Phone = '',
    [uri] $ApiBase = 'http://127.0.0.1:8080/api'
)
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
if ($ApiBase.Scheme -ne 'https' -and -not $ApiBase.IsLoopback) {
    throw 'Use HTTPS for a remote platform API. HTTP is allowed for loopback verification only.'
}
function Get-PlainText([Security.SecureString] $Value) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
$key = $env:PLATFORM_PROVISIONING_KEY
if (-not $key) { $key = Get-PlainText (Read-Host 'Platform provisioning key' -AsSecureString) }
if ($utf8.GetByteCount($key) -lt 32) { throw 'The platform provisioning key must contain at least 32 UTF-8 bytes.' }
$password = Get-PlainText (Read-Host 'Initial admin password (at least 8 characters)' -AsSecureString)
$confirm = Get-PlainText (Read-Host 'Confirm initial admin password' -AsSecureString)
try {
    if ($password -cne $confirm) { throw 'Passwords do not match.' }
    if ($password.Length -lt 8 -or $utf8.GetByteCount($password) -gt 72) { throw 'Password must contain at least 8 characters and at most 72 UTF-8 bytes.' }
    $body = @{ code = $Code; name = $Name; adminName = $AdminName; adminPassword = $password; phone = $Phone } | ConvertTo-Json -Compress
    $url = $ApiBase.AbsoluteUri.TrimEnd('/') + '/platform/tenants'
    $response = Invoke-RestMethod -Method Post -Uri $url -Headers @{ 'X-Platform-Key' = $key } -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes($body)
    if ($response.code -ne 200) { throw ('Provisioning failed: ' + $response.message) }
    $response.data | Format-List tenantId, tenantCode, adminUserId, adminUsername
    Write-Host 'Enterprise created. Deliver the enterprise code and credentials to the owner through a secure channel.'
} finally {
    $password = $null; $confirm = $null; $key = $null; $body = $null
}
