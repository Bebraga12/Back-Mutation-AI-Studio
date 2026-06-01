[CmdletBinding()]
param(
    [string]$InstallRoot = "$env:LOCALAPPDATA\Mutation AI Studio",
    [switch]$Force,
    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"

function Resolve-ProjectRoot {
    param([string]$ExplicitRoot)

    if ($ExplicitRoot) {
        return (Resolve-Path $ExplicitRoot).Path
    }

    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-JavaExecutable {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "Java 21 ou superior não foi encontrado. Instale o JDK e defina JAVA_HOME ou adicione java.exe ao PATH."
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    $versionLine = & $JavaExe -version 2>&1 | Select-Object -First 1
    if ($versionLine -match 'version "(?:1\.)?(?<major>\d+)') {
        return [int]$Matches.major
    }

    return 0
}

function Find-BuiltJar {
    param([string]$ProjectRoot)

    $targetDir = Join-Path $ProjectRoot "target"
    if (-not (Test-Path $targetDir)) {
        return $null
    }

    $jar = Get-ChildItem -Path $targetDir -Filter "mutation-ai-studio-*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object Name |
        Select-Object -Last 1

    return $jar
}

function Invoke-Build {
    param([string]$ProjectRoot)

    $mvnw = Join-Path $ProjectRoot "mvnw.cmd"
    if (-not (Test-Path $mvnw)) {
        throw "Maven Wrapper não encontrado em $ProjectRoot."
    }

    Push-Location $ProjectRoot
    try {
        & $mvnw -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Falha ao compilar o projeto com Maven Wrapper."
        }
    } finally {
        Pop-Location
    }
}

function Update-UserPath {
    param([string]$BinDir)

    $current = [Environment]::GetEnvironmentVariable("Path", "User")
    $segments = @()
    if ($current) {
        $segments = $current -split ';' | Where-Object { $_ -and $_.Trim() }
    }

    if ($segments -contains $BinDir) {
        return
    }

    $newPath = if ($segments.Count -gt 0) {
        (@($BinDir) + $segments) -join ';'
    } else {
        $BinDir
    }

    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    $env:Path = if ($env:Path) { "$BinDir;$env:Path" } else { $BinDir }
}

$projectRoot = Resolve-ProjectRoot $ProjectRoot
$javaExe = Get-JavaExecutable
$javaMajor = Get-JavaMajorVersion -JavaExe $javaExe
if ($javaMajor -lt 21) {
    throw "Java 21 ou superior é necessário. Versão detectada: $javaMajor."
}

$jar = Find-BuiltJar -ProjectRoot $projectRoot
if (-not $jar) {
    Write-Host "Jar não encontrado. Compilando o projeto primeiro..." -ForegroundColor Yellow
    Invoke-Build -ProjectRoot $projectRoot
    $jar = Find-BuiltJar -ProjectRoot $projectRoot
}

if (-not $jar) {
    throw "Não foi possível localizar o jar gerado em $projectRoot\target."
}

$installRoot = [IO.Path]::GetFullPath($InstallRoot)
$appDir = Join-Path $installRoot "app"
$binDir = Join-Path $installRoot "bin"

if (Test-Path $installRoot) {
    if (-not $Force) {
        throw "A instalação já existe em $installRoot. Use -Force para sobrescrever."
    }
}

New-Item -ItemType Directory -Force -Path $appDir, $binDir | Out-Null

Copy-Item -Path $jar.FullName -Destination (Join-Path $appDir "mutation-ai-studio.jar") -Force
Copy-Item -Path (Join-Path $PSScriptRoot "mutation-ai.cmd") -Destination (Join-Path $binDir "mutation-ai.cmd") -Force

Update-UserPath -BinDir $binDir

Write-Host "Mutation AI Studio instalado em: $installRoot" -ForegroundColor Green
Write-Host "Comando disponível: mutation-ai" -ForegroundColor Green
Write-Host "Se o terminal atual não enxergar o PATH atualizado, abra uma nova sessão." -ForegroundColor DarkYellow
