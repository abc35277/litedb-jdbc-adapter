param(
    [string]$Version = "",
    [string]$JavaHome = "",
    [switch]$SkipSmoke
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dist = Join-Path $root "dist"

# Version single source (FIX-405): Directory.Build.props.
if (-not $Version) {
    $buildProps = [xml](Get-Content -Raw (Join-Path $root "Directory.Build.props"))
    $Version = $buildProps.Project.PropertyGroup.Version
}
if (-not $Version) {
    throw "Version is not set: pass -Version or define <Version> in Directory.Build.props."
}

$releaseRoot = Join-Path $root "release"
$releaseDir = Join-Path $releaseRoot "v$Version"

if (-not $JavaHome) {
    if ($env:JAVA_HOME) {
        $JavaHome = $env:JAVA_HOME
    } elseif (Test-Path "E:\dev\env\jdk-26.0.1") {
        $JavaHome = "E:\dev\env\jdk-26.0.1"
    }
}

& (Join-Path $root "build.ps1") -JavaHome $JavaHome -Version $Version

if (-not $SkipSmoke) {
    & (Join-Path $root "scripts\smoke.ps1") -JavaHome $JavaHome -Version $Version
}

if (Test-Path $releaseDir) {
    Remove-Item -Recurse -Force $releaseDir
}
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

$baseName = "litedb-jdbc-adapter-$Version"
$packageDir = Join-Path $releaseDir $baseName
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $packageDir "bridge") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $packageDir "dbeaver") | Out-Null

$builtJar = Join-Path $dist "litedb-jdbc-adapter-$Version.jar"
if (-not (Test-Path $builtJar)) {
    $builtJar = Join-Path $dist "litedb-jdbc-adapter.jar"
}
Copy-Item $builtJar (Join-Path $packageDir "litedb-jdbc-adapter.jar")
$builtBridge = Join-Path $dist "bridge-$Version"
if (-not (Test-Path $builtBridge)) {
    $builtBridge = Join-Path $dist "bridge"
}
Get-ChildItem (Join-Path $builtBridge "*") -Exclude *.pdb |
    Copy-Item -Destination (Join-Path $packageDir "bridge") -Recurse -Force
$driversXmlPath = Join-Path $packageDir "dbeaver\drivers.xml"
Copy-Item (Join-Path $dist "dbeaver\drivers.xml") $driversXmlPath
[xml]$driversXml = Get-Content -Raw $driversXmlPath
@($driversXml.drivers.provider.driver.library) | Where-Object { $_.path -like 'litedb-jdbc-adapter*.jar' } | ForEach-Object {
    $_.SetAttribute('path', 'litedb-jdbc-adapter.jar')
}
@($driversXml.drivers.provider.driver.property) | Where-Object { $_.name -eq 'bridgePath' } | ForEach-Object {
    $_.SetAttribute('value', 'bridge')
}
$driversXml.Save($driversXmlPath)
Copy-Item (Join-Path $root "README.md") (Join-Path $packageDir "README.md")
Copy-Item (Join-Path $root "LICENSE") (Join-Path $packageDir "LICENSE")
Copy-Item (Join-Path $root "THIRD-PARTY-NOTICES.md") (Join-Path $packageDir "THIRD-PARTY-NOTICES.md")
Copy-Item (Join-Path $root "CHANGELOG.md") (Join-Path $packageDir "CHANGELOG.md")

$releaseNotes = Join-Path $packageDir "RELEASE_NOTES.md"
# Single-quoted here-string: backtick sequences such as `bridge must survive verbatim
# (a double-quoted here-string turned `b into a 0x08 backspace control character).
@'
# LiteDB JDBC Adapter {0}

Release date: {1}

## Included

- `litedb-jdbc-adapter.jar` Java JDBC driver.
- `bridge\LiteDbJdbc.Bridge.exe` and bridge runtime files.
- `dbeaver\drivers.xml` DBeaver generic-driver reference.
- `README.md` setup and query examples.

## DBeaver quick setup

1. Add `litedb-jdbc-adapter.jar` as a Generic JDBC driver.
2. Class name: `com.litedb.jdbc.LiteDbDriver`.
3. URL template: `jdbc:litedb:{{file}}`.
4. Driver property `bridgePath`: path to this package's `bridge` directory.

## Verified

- Java service-provider registration exists in the jar.
- Smoke test opens a sample LiteDB database.
- Metadata calls enumerate tables, columns, primary key, and indexes.
- PreparedStatement binding works.
- Concurrent DBeaver test/open connections use LiteDB shared mode to avoid stale file locks.
- DBeaver password/native authentication can pass LiteDB encrypted-database passwords.
- Unicode database paths and packaged relative bridge paths are validated by smoke tests.
- Build scripts can produce a versioned jar when DBeaver is locking the stable jar.
'@ -f $Version, (Get-Date -Format yyyy-MM-dd) | Set-Content -Encoding UTF8 $releaseNotes

$zipPath = Join-Path $releaseDir "$baseName-dbeaver.zip"
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}
Compress-Archive -Path (Join-Path $packageDir "*") -DestinationPath $zipPath

$checksums = Join-Path $releaseDir "SHA256SUMS.txt"
$checksumTargets = @(
    Get-Item $zipPath
    Get-Item (Join-Path $packageDir "litedb-jdbc-adapter.jar")
    Get-Item (Join-Path $packageDir "bridge\LiteDbJdbc.Bridge.exe")
    Get-Item (Join-Path $packageDir "bridge\LiteDbJdbc.Bridge.dll")
)
$checksumTargets |
    Sort-Object FullName |
    ForEach-Object {
        $hash = Get-FileHash -Algorithm SHA256 $_.FullName
        # [System.IO.Path]::GetRelativePath is .NET Core only; Windows PowerShell 5
        # runs on .NET Framework, so compute the relative path manually.
        $relative = $_.FullName.Substring($releaseDir.Length).TrimStart('\') -replace '\\', '/'
        "$($hash.Hash.ToLowerInvariant())  $relative"
    } |
    Set-Content -Encoding ASCII $checksums

Write-Host "Release created: $releaseDir"
Write-Host "Package: $zipPath"
Write-Host "Checksums: $checksums"
