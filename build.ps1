param(
    [string]$Configuration = "Release",
    [string]$JavaHome = "",
    [string]$Version = "",
    [switch]$SkipBridge,
    [switch]$SkipDriver
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Version single source (FIX-405): Directory.Build.props drives the bridge assembly,
# these scripts, and (via the jar manifest) the Java driver.
if (-not $Version) {
    $buildProps = [xml](Get-Content -Raw (Join-Path $root "Directory.Build.props"))
    $Version = $buildProps.Project.PropertyGroup.Version
}
if (-not $Version) {
    throw "Version is not set: pass -Version or define <Version> in Directory.Build.props."
}

$dist = Join-Path $root "dist"
$classes = Join-Path $dist "classes"
$bridgeOut = Join-Path $dist "bridge"
$versionedBridgeOut = Join-Path $dist "bridge-$Version"

if (-not $JavaHome) {
    if ($env:JAVA_HOME) {
        $JavaHome = $env:JAVA_HOME
    } elseif (Test-Path "E:\dev\env\jdk-26.0.1") {
        $JavaHome = "E:\dev\env\jdk-26.0.1"
    }
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null

if (-not $SkipBridge) {
    dotnet publish (Join-Path $root "src\bridge\LiteDbJdbc.Bridge\LiteDbJdbc.Bridge.csproj") `
        -c $Configuration `
        -o $versionedBridgeOut `
        --nologo
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed with exit code $LASTEXITCODE"
    }

    try {
        New-Item -ItemType Directory -Force -Path $bridgeOut | Out-Null
        Copy-Item -Force -Recurse (Join-Path $versionedBridgeOut "*") $bridgeOut
        $packageBridgePath = $bridgeOut
    } catch {
        $packageBridgePath = $versionedBridgeOut
        Write-Warning "Could not update $bridgeOut because it is locked. Built versioned bridge instead: $versionedBridgeOut"
    }
} else {
    $packageBridgePath = if (Test-Path $versionedBridgeOut) { $versionedBridgeOut } else { $bridgeOut }
}

if (-not $SkipDriver) {
    if (-not $JavaHome) {
        throw "JavaHome is not set. Pass -JavaHome or set JAVA_HOME."
    }

    $javac = Join-Path $JavaHome "bin\javac.exe"
    $jar = Join-Path $JavaHome "bin\jar.exe"
    if (-not (Test-Path $javac)) {
        throw "javac was not found at $javac"
    }
    if (-not (Test-Path $jar)) {
        throw "jar was not found at $jar"
    }

    if (Test-Path $classes) {
        Remove-Item -Recurse -Force $classes
    }
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $sources = Join-Path $dist "java-sources.txt"
    Get-ChildItem -Path (Join-Path $root "src\driver") -Recurse -Filter "*.java" |
        Sort-Object FullName |
        ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
        Set-Content -Encoding ASCII $sources

    & $javac --release 11 -encoding UTF-8 -d $classes "@$sources"
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    $manifest = Join-Path $dist "MANIFEST.MF"
    @(
        "Manifest-Version: 1.0"
        "Implementation-Title: LiteDB JDBC Adapter"
        "Implementation-Version: $Version"
        "Automatic-Module-Name: com.litedb.jdbc"
        ""
    ) | Set-Content -Encoding ASCII $manifest

    $versionedJarPath = Join-Path $dist "litedb-jdbc-adapter-$Version.jar"
    if (Test-Path $versionedJarPath) {
        Remove-Item -Force $versionedJarPath
    }
    & $jar --create --file $versionedJarPath --manifest $manifest -C $classes . -C (Join-Path $root "src\driver") META-INF
    if ($LASTEXITCODE -ne 0) {
        throw "jar failed with exit code $LASTEXITCODE"
    }

    $jarPath = Join-Path $dist "litedb-jdbc-adapter.jar"
    $packageJarPath = $jarPath
    try {
        Copy-Item -Force $versionedJarPath $jarPath
    } catch {
        $packageJarPath = $versionedJarPath
        Write-Warning "Could not update $jarPath because it is locked. Built versioned jar instead: $versionedJarPath"
    }

    $dbeaverDir = Join-Path $dist "dbeaver"
    New-Item -ItemType Directory -Force -Path $dbeaverDir | Out-Null
    $driverLibraryName = Split-Path -Leaf $packageJarPath
    $driverBridgePathName = Split-Path -Leaf $packageBridgePath
    @"
<?xml version="1.0" encoding="UTF-8"?>
<drivers>
  <provider id="generic">
  <driver id="litedb_jdbc" categories="sql,embedded,nosql" name="LiteDB" class="com.litedb.jdbc.LiteDbDriver" url="jdbc:litedb:{file}" sampleURL="jdbc:litedb:{file}" description="LiteDB JDBC Adapter" custom="true" embedded="true" anonymous="false" allowsEmptyPassword="true" supportedConfigurationTypes="MANUAL,URL" propagateDriverProperties="true">
    <library type="jar" path="$driverLibraryName" custom="true"/>
    <file type="database" required="true"/>
    <property name="bridgePath" value="$driverBridgePathName"/>
    <property name="connection" value="shared"/>
    <property name="readOnly" value="false"/>
    <parameter name="supports-indexes" value="true"/>
    <parameter name="supports-table-constraints" value="true"/>
    <parameter name="supports-references" value="false"/>
    <parameter name="supports-stored-code" value="false"/>
    <parameter name="supports-views" value="false"/>
    <parameter name="supports-schema-selection" value="false"/>
    <parameter name="supports-catalog-selection" value="false"/>
    <parameter name="omit-schema" value="true"/>
    <parameter name="omit-single-catalog" value="true"/>
    <parameter name="omit-catalog" value="false"/>
    <parameter name="omit-single-schema" value="true"/>
    <parameter name="read-only-meta-data" value="true"/>
    <parameter name="supports-scroll" value="true"/>
    <parameter name="supports-limits" value="true"/>
    <parameter name="supports-select-count" value="true"/>
    <parameter name="supports-struct-cache" value="false"/>
    <parameter name="read-only-data" value="false"/>
  </driver>
  </provider>
</drivers>
"@ | Set-Content -Encoding UTF8 (Join-Path $dbeaverDir "drivers.xml")

    $zipPath = Join-Path $dist "litedb-jdbc-adapter-dbeaver.zip"
    if (Test-Path $zipPath) {
        Remove-Item -Force $zipPath
    }
    Compress-Archive -Path $packageJarPath, $packageBridgePath, $dbeaverDir -DestinationPath $zipPath
}

Write-Host "Build output: $dist"
