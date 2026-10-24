# Windows convenience entry: .\build.ps1 [Maven arguments, default: package]
# Maven and all downloaded dependencies stay in the ignored .tools directory.
$ErrorActionPreference = 'Stop'
$mavenVersion = '3.9.9'
$distributionHash = '8BEAC8D11EF208F1E2A8DF0682B9448A9A363D2AD13CA74AF43705549E72E74C9378823BF689287801CBBFC2F6EA9596201D19CCACFDFB682EE8A2FF4C4418BA'
$toolRoot = Join-Path $PSScriptRoot '.tools'
$mavenPath = Join-Path $toolRoot "apache-maven-$mavenVersion\bin\mvn.cmd"
[string[]]$mavenArguments = if ($args.Count -eq 0) { @('package') } else { $args }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'JDK 17+ is required. Add its bin directory to PATH.' }
if (-not (Test-Path -LiteralPath $mavenPath)) {
    New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
    $archivePath = Join-Path $toolRoot "apache-maven-$mavenVersion-bin.zip"
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Write-Host "Downloading Apache Maven $mavenVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip" -OutFile $archivePath
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $distributionHash) {
        throw "Maven checksum mismatch. Remove the archive at $archivePath and retry."
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
}

Push-Location $PSScriptRoot
try {
    & $mavenPath '-B' '-ntp' "-Dmaven.repo.local=$toolRoot\repository" @mavenArguments
    $buildExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExitCode
