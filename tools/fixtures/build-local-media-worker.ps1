param(
    [Parameter(Mandatory = $true)][string]$BackendServicePath,
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$mobileRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backendService = (Resolve-Path -LiteralPath $BackendServicePath).Path
if (-not (Select-String -LiteralPath (Join-Path $backendService 'go.mod') -Pattern '^module github.com/floopel/backend$' -Quiet)) {
    throw 'Provide the canonical backend application/service directory'
}
if (-not $OutputPath) { $OutputPath = Join-Path $mobileRoot '.gradle/runtime/private-media-worker.exe' }
$workerOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
& git -C $mobileRoot check-ignore -- $workerOutput | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The compiled fixture worker must be ignored by the mobile repository' }
$helperRelative = 'tmp/android-traveler-media-' + [guid]::NewGuid().ToString('N')
& git -C $backendService check-ignore -- ($helperRelative + '/main.go') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The temporary helper must be ignored by the backend repository' }
$helperPath = Join-Path $backendService $helperRelative
New-Item -ItemType Directory -Path $helperPath | Out-Null
foreach ($template in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'local-media-worker') -Filter '*.go.template') {
    Copy-Item -LiteralPath $template.FullName -Destination (Join-Path $helperPath $template.Name.Replace('.template', ''))
}
New-Item -ItemType Directory -Path (Split-Path -Parent $workerOutput) -Force | Out-Null
Push-Location $backendService
try {
    & go build -mod=readonly -o $workerOutput ('./' + $helperRelative)
    if ($LASTEXITCODE -ne 0) { throw 'Fixture media worker build failed' }
} finally { Pop-Location }
Write-Output $workerOutput
