param(
    [Parameter(Mandatory = $true)][string]$BackendServicePath,
    [string]$OutputPath,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$mobileRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backendService = (Resolve-Path -LiteralPath $BackendServicePath).Path
if (-not (Select-String -LiteralPath (Join-Path $backendService 'go.mod') -Pattern '^module github.com/floopel/backend$' -Quiet)) {
    throw 'Provide the canonical backend application/service directory'
}
if (-not $OutputPath) { $OutputPath = Join-Path $mobileRoot '.gradle/runtime/private-fixture.json' }
$credentialPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
& git -C $mobileRoot check-ignore -- $credentialPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The fixture credential output must be ignored by the mobile repository' }
$helperRelative = 'tmp/android-traveler-fixture-' + [guid]::NewGuid().ToString('N')
& git -C $backendService check-ignore -- ($helperRelative + '/main.go') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The temporary helper must be ignored by the backend repository' }
$helperPath = Join-Path $backendService $helperRelative
New-Item -ItemType Directory -Path $helperPath | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'traveler-local.go.template') -Destination (Join-Path $helperPath 'main.go')
Push-Location $backendService
try {
    $arguments = @('run', '-mod=readonly', ('./' + $helperRelative))
    if (-not $CheckOnly) { $arguments += @('--seed', '--output', $credentialPath) }
    & go @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Local fixture preflight or creation failed; inspect the sanitized status code' }
} finally { Pop-Location }
