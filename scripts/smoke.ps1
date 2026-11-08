param(
    [string]$JavaHome = "",
    [string]$DatabasePath = "",
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# Version single source (FIX-405): Directory.Build.props.
if (-not $Version) {
    $buildProps = [xml](Get-Content -Raw (Join-Path $root "Directory.Build.props"))
    $Version = $buildProps.Project.PropertyGroup.Version
}
if (-not $Version) {
    throw "Version is not set: pass -Version or define <Version> in Directory.Build.props."
}

function Assert-LastExitCode {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}
if (-not $JavaHome) {
    if ($env:JAVA_HOME) {
        $JavaHome = $env:JAVA_HOME
    } elseif (Test-Path "E:\dev\env\jdk-26.0.1") {
        $JavaHome = "E:\dev\env\jdk-26.0.1"
    }
}
if (-not $JavaHome) {
    throw "JavaHome is not set. Pass -JavaHome or set JAVA_HOME."
}

& (Join-Path $root "build.ps1") -JavaHome $JavaHome -Version $Version
Assert-LastExitCode "build"

if (-not $DatabasePath) {
    $DatabasePath = Join-Path $root "dist\sample.litedb"
}

dotnet run --project (Join-Path $root "src\tools\LiteDbJdbc.SampleDb\LiteDbJdbc.SampleDb.csproj") -- $DatabasePath
Assert-LastExitCode "sample database generation"

$javac = Join-Path $JavaHome "bin\javac.exe"
$java = Join-Path $JavaHome "bin\java.exe"
$versionedJar = Join-Path $root "dist\litedb-jdbc-adapter-$Version.jar"
$jar = if (Test-Path $versionedJar) { $versionedJar } else { Join-Path $root "dist\litedb-jdbc-adapter.jar" }
$versionedBridge = Join-Path $root "dist\bridge-$Version"
$bridge = if (Test-Path $versionedBridge) { $versionedBridge } else { Join-Path $root "dist\bridge" }
$testClasses = Join-Path $root "dist\test-classes"
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null

& $javac --release 11 -cp $jar -d $testClasses `
    (Join-Path $root "tests\java\SmokeTest.java") `
    (Join-Path $root "tests\java\ComplexDbSmokeTest.java")
Assert-LastExitCode "javac smoke test"
& $java -cp "$jar;$testClasses" SmokeTest "jdbc:litedb:$DatabasePath" $bridge
Assert-LastExitCode "smoke test with explicit bridgePath"

# Verify bundled bridge auto-discovery when bridgePath is not passed.
& $java -cp "$jar;$testClasses" SmokeTest "jdbc:litedb:$DatabasePath"
Assert-LastExitCode "smoke test with bundled bridge auto-discovery"

$encryptedPath = Join-Path $root "dist\sample-encrypted.litedb"
$password = "LiteDB-P@ssw0rd!"
dotnet run --project (Join-Path $root "src\tools\LiteDbJdbc.SampleDb\LiteDbJdbc.SampleDb.csproj") -- $encryptedPath $password
Assert-LastExitCode "encrypted sample database generation"
& $java -cp "$jar;$testClasses" SmokeTest "jdbc:litedb:$encryptedPath" $bridge $password
Assert-LastExitCode "encrypted database smoke test"

$unicodePath = Join-Path $root "dist\中文路径\Unicode.Service\bin\Debug\net7.0\UnicodeSample.db"
dotnet run --project (Join-Path $root "src\tools\LiteDbJdbc.SampleDb\LiteDbJdbc.SampleDb.csproj") -- $unicodePath
Assert-LastExitCode "unicode sample database generation"
& $java -cp "$jar;$testClasses" SmokeTest "jdbc:litedb:$unicodePath" $bridge
Assert-LastExitCode "unicode path smoke test"

try {
    [System.Text.Encoding]::RegisterProvider([System.Text.CodePagesEncodingProvider]::Instance)
    $mojibakePath = [System.Text.Encoding]::GetEncoding(936).GetString([System.Text.Encoding]::UTF8.GetBytes($unicodePath))
    if ($mojibakePath -and $mojibakePath -ne $unicodePath) {
        & $java -cp "$jar;$testClasses" SmokeTest "jdbc:litedb:$mojibakePath" $bridge
        Assert-LastExitCode "mojibake path repair smoke test"
    }
} catch {
    Write-Warning "Skipping mojibake path smoke test: $($_.Exception.Message)"
}

$complexDir = Join-Path $root "dist\testdb"
& (Join-Path $root "scripts\generate-complex-db.ps1") -OutputDir $complexDir
Assert-LastExitCode "complex database generation"
$complexPath = Join-Path $complexDir "ComplexTest.db"
$complexEncryptedPath = Join-Path $complexDir "ComplexTest-encrypted.db"
$complexPassword = "Complex@123456"
& $java -cp "$jar;$testClasses" ComplexDbSmokeTest "jdbc:litedb:$complexPath" $bridge
Assert-LastExitCode "complex database smoke test"
& $java -cp "$jar;$testClasses" ComplexDbSmokeTest "jdbc:litedb:$complexEncryptedPath" $bridge $complexPassword
Assert-LastExitCode "encrypted complex database smoke test"
