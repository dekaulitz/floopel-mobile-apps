<#
.SYNOPSIS
Imports one Floopel client environment into the current PowerShell process.
.DESCRIPTION
Reads literal KEY=value assignments without executing expressions or expanding variables.
Values are not printed. Exported variables are inherited by Gradle/child processes.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('development', 'production')]
    [string]$Environment,
    [string]$Path
)

$ErrorActionPreference = 'Stop'
if (-not $Path) {
    $Path = Join-Path $PSScriptRoot "../../config/environments/$Environment.env"
}
$resolvedPath = (Resolve-Path -LiteralPath $Path).Path
$assignments = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $resolvedPath -Encoding UTF8) {
    $lineNumber++
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    if ($entry -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
        throw "Invalid KEY=value assignment at line $lineNumber."
    }
    $key = $Matches[1]
    $value = $Matches[2].Trim()
    $environmentPrefix = 'FLOOPEL_' + $Environment.ToUpperInvariant() + '_'
    $isEnvironmentInput = $key.StartsWith($environmentPrefix, [StringComparison]::Ordinal)
    $isVersionInput = $key -match '^FLOOPEL_(TRAVELER|PARTNER)_VERSION_(CODE|NAME)$'
    $isSigningInput = $Environment -eq 'production' -and $key -match '^FLOOPEL_(TRAVELER|PARTNER)_PRODUCTION_(KEYSTORE_FILE|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$'
    if (-not ($isEnvironmentInput -or $isVersionInput -or $isSigningInput)) {
        throw "Unexpected variable $key at line $lineNumber for $Environment."
    }
    if ($assignments.ContainsKey($key)) { throw "Duplicate variable $key at line $lineNumber." }
    if ($value.StartsWith('"') -or $value.StartsWith("'")) {
        if ($value.Length -lt 2 -or $value[0] -ne $value[$value.Length - 1]) {
            throw "Unclosed quoted value for $key at line $lineNumber."
        }
        $value = $value.Substring(1, $value.Length - 2)
    }
    $assignments.Add($key, $value)
}
# Validate the whole file before changing any process variables.
foreach ($assignment in $assignments.GetEnumerator()) {
    [System.Environment]::SetEnvironmentVariable($assignment.Key, $assignment.Value, 'Process')
}
Write-Output "Imported $($assignments.Count) $Environment client variables into this process."
