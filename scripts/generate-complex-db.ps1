param(
    [string]$OutputDir = "",
    [string]$Password = "Complex@123456",
    [switch]$SkipEncrypted
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $OutputDir) {
    $OutputDir = Join-Path $root "dist\testdb"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$project = Join-Path $root "src\tools\LiteDbJdbc.ComplexDb\LiteDbJdbc.ComplexDb.csproj"
$plain = Join-Path $OutputDir "ComplexTest.db"
$encrypted = Join-Path $OutputDir "ComplexTest-encrypted.db"

dotnet run --project $project -- $plain
if ($LASTEXITCODE -ne 0) {
    throw "plain complex database generation failed with exit code $LASTEXITCODE"
}

if (-not $SkipEncrypted) {
    dotnet run --project $project -- $encrypted $Password
    if ($LASTEXITCODE -ne 0) {
        throw "encrypted complex database generation failed with exit code $LASTEXITCODE"
    }
}

Write-Host "Plain DB: $plain"
if (-not $SkipEncrypted) {
    Write-Host "Encrypted DB: $encrypted"
    Write-Host "Encrypted password: $Password"
}
