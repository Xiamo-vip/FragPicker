param([string]$LauncherPath = (Join-Path $PSScriptRoot 'Start-Server.ps1'))

# Synthetic configuration only: -Check never connects to MySQL or cloud services.
$ErrorActionPreference = 'Stop'
$launcher = (Resolve-Path -LiteralPath $LauncherPath).Path
$workspace = Split-Path (Split-Path $PSScriptRoot)
$testRoot = Join-Path $workspace ('.tools/startup-config-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$flags = @('AI_CHAT_ENABLED','PARSEVIDEO_ENABLED','OSS_ENABLED','TINGWU_ENABLED','INGESTION_WORKER_ENABLED',
    'MEDIA_WORKER_ENABLED','TRANSCRIPTION_WORKER_ENABLED','KNOWLEDGE_ENRICHMENT_ENABLED','KNOWLEDGE_INDEX_ENABLED',
    'DIGEST_WORKER_ENABLED','DIGEST_SCHEDULE_ENABLED','MEDIA_CLEANUP_ENABLED')
$base = @{
    DB_URL = 'jdbc:mysql://localhost:1/fixture'
    DB_USERNAME = 'fixture'
    DB_PASSWORD = 'fixture-only-password'
    JWT_SIGNING_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    AI_CHAT_API_KEY = 'fixture-only-ai-key'
    AI_CHAT_MODEL = 'fixture-model'
    PARSEVIDEO_BASE_URL = 'http://127.0.0.1:1/'
    OSS_BUCKET = 'fixture-only-bucket'
    OSS_ENDPOINT = 'oss-cn-shenzhen.aliyuncs.com'
    ALIBABA_CLOUD_ACCESS_KEY_ID = 'fixture-only-id'
    ALIBABA_CLOUD_ACCESS_KEY_SECRET = 'fixture-only-secret'
    TINGWU_APP_KEY = 'fixture-only-app'
}
foreach ($name in $flags) { $base[$name] = 'true' }
$tuning = @{
    TRANSCRIPTION_POLL_DELAY = '2s'
    TRANSCRIPTION_QUERY_DELAY = '1m'
    TRANSCRIPTION_LEASE_DURATION = '5m'
    TRANSCRIPTION_MAX_TASK_AGE = '24h'
    TRANSCRIPTION_MAX_FAILURES = '8'
    TRANSCRIPTION_RETRY_BASE_DELAY = '10s'
}
$configNames = @()
foreach ($file in @('application.yml','application-database.yml')) {
    $yaml = [IO.File]::ReadAllText((Join-Path (Split-Path $PSScriptRoot) "src/main/resources/$file"))
    $configNames += @([regex]::Matches($yaml, '\$\{([A-Z][A-Z0-9_]*)') | ForEach-Object { $_.Groups[1].Value })
}
$names = @($configNames + @($base.Keys) + @($tuning.Keys) + @('INDEX_RUNTIME_DIRECTORY') | Sort-Object -Unique)
$saved = @{}
foreach ($name in $names) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $null)
}

function Write-Fixture([hashtable]$Values) {
    $path = Join-Path $testRoot ([guid]::NewGuid().ToString('N') + '.env')
    $lines = @($Values.Keys | Sort-Object | ForEach-Object { "$_=$($Values[$_])" })
    [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
    return $path
}
function Check-Configuration([string]$Path) {
    $output = @(& $launcher -EnvFile $Path -Check)
    if ($output.Count -ne 1 -or $output[0] -ne 'Application configuration validated; database/cloud connectivity was not checked.') {
        throw 'Unexpected configuration-check output'
    }
}
function Expect-Rejection([string]$Path, [string]$Expected) {
    $caught = $null
    try { Check-Configuration $Path } catch { $caught = $_.Exception.Message }
    if ($caught -ne $Expected) { throw "Expected rejection was not observed: $Expected" }
    if ($caught -match 'fixture-only') { throw 'A rejection exposed a fixture credential' }
}
function Assert-EnvironmentRestored {
    foreach ($name in $names) {
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name))) {
            throw "Configuration loading did not restore the environment: $name"
        }
    }
}

try {
    Check-Configuration (Write-Fixture $base)
    Assert-EnvironmentRestored
    Write-Output 'PASS full enabled configuration, including transcription worker'

    $values = $base.Clone()
    foreach ($name in $tuning.Keys) { $values[$name] = $tuning[$name] }
    Check-Configuration (Write-Fixture $values)
    Assert-EnvironmentRestored
    Write-Output 'PASS all transcription tuning variables'

    $values = @{}
    foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','JWT_SIGNING_KEY')) { $values[$name] = $base[$name] }
    foreach ($name in $flags) { $values[$name] = 'false' }
    Check-Configuration (Write-Fixture $values)
    Assert-EnvironmentRestored
    Write-Output 'PASS local mode with external services disabled'

    $values = $base.Clone()
    $values['DB_URL'] = 'invalid-file-url'
    $env:DB_URL = $base['DB_URL']
    try {
        Check-Configuration (Write-Fixture $values)
        if ($env:DB_URL -ne $base['DB_URL']) { throw 'Existing process environment was not preserved' }
    } finally { $env:DB_URL = $null }
    Assert-EnvironmentRestored
    Write-Output 'PASS existing process environment takes precedence'

    $values = $base.Clone(); $values['AI_CHAT_API_KEY'] = ''
    Expect-Rejection (Write-Fixture $values) 'Missing environment variable: AI_CHAT_API_KEY'
    Assert-EnvironmentRestored
    Write-Output 'PASS missing enabled-service credential rejected without disclosure'

    $values = $base.Clone(); $values['TINGWU_ENABLED'] = 'false'
    Expect-Rejection (Write-Fixture $values) 'TRANSCRIPTION_WORKER_ENABLED requires TINGWU_ENABLED=true'
    Assert-EnvironmentRestored
    Write-Output 'PASS transcription dependency remains enforced'

    $values = $base.Clone(); $values['JWT_SIGNING_KEY'] = [Convert]::ToBase64String([byte[]]::new(16))
    Expect-Rejection (Write-Fixture $values) 'JWT_SIGNING_KEY requires at least 32 random bytes'
    Assert-EnvironmentRestored
    Write-Output 'PASS short JWT key rejected'

    $duplicate = Write-Fixture $base
    [IO.File]::AppendAllText($duplicate, "AI_CHAT_API_KEY=fixture-only-duplicate`n", [Text.UTF8Encoding]::new($false))
    Expect-Rejection $duplicate 'Duplicate environment variable: AI_CHAT_API_KEY'
    Assert-EnvironmentRestored
    Write-Output 'PASS duplicate assignments rejected'

    $values = $base.Clone(); $values['PATH'] = 'fixture-only-forbidden'
    Expect-Rejection (Write-Fixture $values) 'Unsupported application variable: PATH'
    Assert-EnvironmentRestored
    Write-Output 'PASS non-application variables remain forbidden'

    Write-Output '9 startup configuration checks passed; no database/cloud connectivity attempted.'
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}
