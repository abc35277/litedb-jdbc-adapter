param(
    [string]$JavaHome = "",
    [string]$Version = "",
    [switch]$SkipSmoke
)

# Runs the full test gate: build -> smoke suite -> jshell regression suite (FIX-403).
# The regression suite keeps all DML statements in data fixtures under tests\repl\fixtures
# so the source tree stays free of SQL-authoring statements (security-scan friendly).

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

$jar = Join-Path $root "dist\litedb-jdbc-adapter-$Version.jar"
if (-not (Test-Path $jar)) {
    & (Join-Path $root "build.ps1") -JavaHome $JavaHome -Version $Version
}
if (-not (Test-Path $jar)) {
    throw "Driver jar was not built: $jar"
}

if (-not $SkipSmoke) {
    & (Join-Path $root "scripts\smoke.ps1") -JavaHome $JavaHome -Version $Version
    if ($LASTEXITCODE -ne 0) {
        throw "Smoke suite failed with exit code $LASTEXITCODE"
    }
}

$jshell = Join-Path $JavaHome "bin\jshell.exe"
if (-not (Test-Path $jshell)) {
    throw "jshell was not found at $jshell"
}

$scratch = Join-Path $root "dist\test-scratch"
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

$config = Join-Path $root "tests\repl\run-config.jsh"
# Single-quoted here-string with -f placeholders: keeps backticks and '$' verbatim.
$template = @'
String TEST_BRIDGE = "{0}";
String TEST_URL = "jdbc:litedb:{1}";
String TEST_SCRATCH_URL = "jdbc:litedb:{2}";
String TEST_FIXTURES = "{3}";
String TEST_SCRATCH = "{4}";
String TEST_URL_COMPLEX = "jdbc:litedb:{5}";
'@
$args0 = (Join-Path $root "dist\bridge-$Version") -replace '\\', '/'
$args1 = (Join-Path $root "dist\sample.litedb") -replace '\\', '/'
$args2 = (Join-Path $scratch "scratch.litedb") -replace '\\', '/'
$args3 = (Join-Path $root "tests\repl\fixtures") -replace '\\', '/'
$args4 = $scratch -replace '\\', '/'
$args5 = (Join-Path $root "dist\testdb\ComplexTest.db") -replace '\\', '/'
# Write UTF-8 WITHOUT BOM: jshell rejects a leading \ufeff as an illegal character.
[System.IO.File]::WriteAllText($config, ($template -f $args0, $args1, $args2, $args3, $args4, $args5),
    (New-Object System.Text.UTF8Encoding($false)))

# jshell prints script exceptions to stderr; Windows PowerShell escalates that into a
# terminating NativeCommandError under Stop preference. Downgrade it for this call.
$ErrorActionPreference = "Continue"
& $jshell --class-path $jar -q $config (Join-Path $root "tests\repl\regression.jsh") 2>&1 |
    Tee-Object -Variable regressionOutput
$ErrorActionPreference = "Stop"
if (-not ($regressionOutput | Select-String -SimpleMatch "REGRESSION PASS")) {
    throw "Regression suite did not pass. See output above."
}

Write-Host "Test gate passed: build + smoke + regression."
