param(
    [string]$WorkspaceConfig = "",
    [string]$JarPath = "",
    [string]$BridgePath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $WorkspaceConfig) {
    $WorkspaceConfig = Join-Path $env:APPDATA "DBeaverData\workspace6\.metadata\.config\drivers.xml"
}
if (-not $JarPath) {
    $versionedJar = Get-ChildItem -Path (Join-Path $root "dist") -Filter "litedb-jdbc-adapter-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-dbeaver.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $JarPath = if ($versionedJar) { $versionedJar.FullName } else { Join-Path $root "dist\litedb-jdbc-adapter.jar" }
}
if (-not $BridgePath) {
    $versionedBridge = Get-ChildItem -Path (Join-Path $root "dist") -Directory -Filter "bridge-*" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $BridgePath = if ($versionedBridge) { $versionedBridge.FullName } else { Join-Path $root "dist\bridge" }
}

if (-not (Test-Path $JarPath)) {
    throw "Jar not found: $JarPath"
}
if (-not (Test-Path $BridgePath)) {
    throw "Bridge directory not found: $BridgePath"
}

if (Get-Process -Name dbeaver -ErrorAction SilentlyContinue) {
    Write-Warning "DBeaver is currently running. The driver file can be updated now, but restart DBeaver after closing it so it reloads drivers.xml and releases old jars."
}

$configDir = Split-Path -Parent $WorkspaceConfig
New-Item -ItemType Directory -Force -Path $configDir | Out-Null

if (Test-Path $WorkspaceConfig) {
    [xml]$xml = Get-Content -Raw $WorkspaceConfig
} else {
    [xml]$xml = '<?xml version="1.0" encoding="UTF-8"?><drivers></drivers>'
}

$provider = $xml.drivers.provider | Where-Object { $_.id -eq 'generic' } | Select-Object -First 1
if (-not $provider) {
    $provider = $xml.CreateElement('provider')
    $provider.SetAttribute('id', 'generic')
    [void]$xml.drivers.AppendChild($provider)
}

$existingDrivers = @($provider.driver | Where-Object {
    $_.id -eq 'litedb_jdbc' `
        -or $_.class -eq 'com.litedb.jdbc.LiteDbDriver' `
        -or @($_.library | Where-Object { $_.path -like '*litedb-jdbc-adapter*' }).Count -gt 0
})
$driverId = if ($existingDrivers.Count -gt 0 -and $existingDrivers[0].id) { [string]$existingDrivers[0].id } else { 'litedb_jdbc' }
$driverName = if ($existingDrivers.Count -gt 0 -and $existingDrivers[0].name) { [string]$existingDrivers[0].name } else { 'LiteDB' }

$existingDrivers | ForEach-Object {
    [void]$provider.RemoveChild($_)
}

$driver = $xml.CreateElement('driver')
$attributes = [ordered]@{
    id = $driverId
    categories = 'sql,embedded,nosql'
    name = $driverName
    class = 'com.litedb.jdbc.LiteDbDriver'
    url = 'jdbc:litedb:{file}'
    sampleURL = 'jdbc:litedb:{file}'
    description = 'LiteDB JDBC Adapter'
    custom = 'true'
    embedded = 'true'
    anonymous = 'false'
    allowsEmptyPassword = 'true'
    supportedConfigurationTypes = 'MANUAL,URL'
    propagateDriverProperties = 'true'
}
foreach ($item in $attributes.GetEnumerator()) {
    $driver.SetAttribute($item.Key, $item.Value)
}

$library = $xml.CreateElement('library')
$library.SetAttribute('type', 'jar')
$library.SetAttribute('path', (Resolve-Path $JarPath).Path)
$library.SetAttribute('custom', 'true')
[void]$driver.AppendChild($library)

$file = $xml.CreateElement('file')
$file.SetAttribute('type', 'database')
$file.SetAttribute('required', 'true')
[void]$driver.AppendChild($file)

$properties = [ordered]@{
    bridgePath = (Resolve-Path $BridgePath).Path
    connection = 'shared'
    readOnly = 'false'
}
foreach ($item in $properties.GetEnumerator()) {
    $property = $xml.CreateElement('property')
    $property.SetAttribute('name', $item.Key)
    $property.SetAttribute('value', $item.Value)
    [void]$driver.AppendChild($property)
}

$parameters = [ordered]@{
    'supports-indexes' = 'true'
    'supports-table-constraints' = 'true'
    'supports-references' = 'false'
    'supports-stored-code' = 'false'
    'supports-views' = 'false'
    'supports-schema-selection' = 'false'
    'supports-catalog-selection' = 'false'
    'omit-schema' = 'true'
    'omit-single-catalog' = 'true'
    'omit-catalog' = 'false'
    'omit-single-schema' = 'true'
    'read-only-meta-data' = 'true'
    'supports-scroll' = 'true'
    'supports-limits' = 'true'
    'supports-select-count' = 'true'
    'supports-struct-cache' = 'false'
    'read-only-data' = 'false'
}
foreach ($item in $parameters.GetEnumerator()) {
    $parameter = $xml.CreateElement('parameter')
    $parameter.SetAttribute('name', $item.Key)
    $parameter.SetAttribute('value', $item.Value)
    [void]$driver.AppendChild($parameter)
}

[void]$provider.AppendChild($driver)

$settings = [System.Xml.XmlWriterSettings]::new()
$settings.Indent = $true
$settings.Encoding = [System.Text.UTF8Encoding]::new($false)
$writer = [System.Xml.XmlWriter]::Create($WorkspaceConfig, $settings)
$xml.Save($writer)
$writer.Close()

Write-Host "Installed LiteDB driver into $WorkspaceConfig"
Write-Host "Driver jar: $((Resolve-Path $JarPath).Path)"
Write-Host "Bridge path: $((Resolve-Path $BridgePath).Path)"
Write-Host "Restart DBeaver if the driver list was already open."
