[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\..\target\windows-installer"),
    [string]$AppName = "Mutation AI Studio",
    [string]$PackageName = "mutation-ai-studio",
    [string]$Vendor = "Mutation AI Studio",
    [string]$Description = "CLI-first tool for local Java test generation.",
    [switch]$Force
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

function Get-JPackageExecutable {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "jpackage.exe não foi encontrado. Use um JDK 21 completo com jpackage no binário."
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    $versionLine = & $JavaExe -version 2>&1 | Select-Object -First 1
    if ($versionLine -match 'version "(?:1\.)?(?<major>\d+)') {
        return [int]$Matches.major
    }

    return 0
}

function Assert-WindowsPackagingPrerequisites {
    if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
        throw "Este script precisa ser executado no Windows para gerar um instalador .exe."
    }

    $wixLight = Get-Command light.exe -ErrorAction SilentlyContinue
    $wixCandle = Get-Command candle.exe -ErrorAction SilentlyContinue
    if (-not $wixLight -or -not $wixCandle) {
        throw "WiX Toolset não foi encontrado. Instale WiX 3.11+ para permitir o empacotamento .exe com jpackage."
    }
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

function Find-BuiltJar {
    param([string]$ProjectRoot)

    $targetDir = Join-Path $ProjectRoot "target"
    if (-not (Test-Path $targetDir)) {
        return $null
    }

    return Get-ChildItem -Path $targetDir -Filter "mutation-ai-studio-*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object Name |
        Select-Object -Last 1
}

$projectRoot = Resolve-ProjectRoot $ProjectRoot
$javaExe = Get-JavaExecutable
$javaMajor = Get-JavaMajorVersion -JavaExe $javaExe
if ($javaMajor -lt 21) {
    throw "Java 21 ou superior é necessário. Versão detectada: $javaMajor."
}

Assert-WindowsPackagingPrerequisites

$jpackage = Get-JPackageExecutable
$jar = Find-BuiltJar -ProjectRoot $projectRoot
if (-not $jar) {
    Write-Host "Jar não encontrado. Compilando o projeto primeiro..." -ForegroundColor Yellow
    Invoke-Build -ProjectRoot $projectRoot
    $jar = Find-BuiltJar -ProjectRoot $projectRoot
}

if (-not $jar) {
    throw "Não foi possível localizar o jar gerado em $projectRoot\target."
}

$version = ($jar.BaseName -replace '^mutation-ai-studio-', '') -replace '-SNAPSHOT$', ''
$outputDir = [IO.Path]::GetFullPath($OutputDir)
$stagingDir = Join-Path $outputDir "staging"
$inputDir = Join-Path $stagingDir "input"
$tempDir = Join-Path $stagingDir "temp"

if (Test-Path $outputDir) {
    if (-not $Force) {
        throw "A pasta de saída já existe em $outputDir. Use -Force para sobrescrever."
    }

    Remove-Item -Recurse -Force $outputDir
}

New-Item -ItemType Directory -Force -Path $inputDir, $tempDir | Out-Null
Copy-Item -Path $jar.FullName -Destination (Join-Path $inputDir "mutation-ai-studio.jar") -Force

& $jpackage `
    --type exe `
    --dest $outputDir `
    --temp $tempDir `
    --input $inputDir `
    --name $PackageName `
    --app-version $version `
    --vendor $Vendor `
    --description $Description `
    --main-jar "mutation-ai-studio.jar" `
    --main-class "org.springframework.boot.loader.launch.JarLauncher" `
    --win-console `
    --win-shortcut `
    --win-menu `
    --win-dir-chooser `
    --win-menu-group $AppName `
    --install-dir $PackageName

if ($LASTEXITCODE -ne 0) {
    throw "Falha ao gerar o instalador .exe com jpackage."
}

Write-Host "Instalador gerado em: $outputDir" -ForegroundColor Green
