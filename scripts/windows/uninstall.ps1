[CmdletBinding()]
param(
    [string]$InstallRoot = "$env:LOCALAPPDATA\Mutation AI Studio"
)

$ErrorActionPreference = "Stop"

function Update-UserPathRemoval {
    param([string]$BinDir)

    $current = [Environment]::GetEnvironmentVariable("Path", "User")
    if (-not $current) {
        return
    }

    $segments = $current -split ';' | Where-Object { $_ -and $_.Trim() -and $_ -ne $BinDir }
    [Environment]::SetEnvironmentVariable("Path", ($segments -join ';'), "User")
}

$installRoot = [IO.Path]::GetFullPath($InstallRoot)
$binDir = Join-Path $installRoot "bin"

Update-UserPathRemoval -BinDir $binDir

if (Test-Path $installRoot) {
    Remove-Item -Recurse -Force $installRoot
}

Write-Host "Mutation AI Studio removido de: $installRoot"
