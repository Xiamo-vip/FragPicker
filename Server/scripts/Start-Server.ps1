param(
    [string]$EnvFile = (Join-Path (Split-Path $PSScriptRoot) '.env'),
    [string]$JarPath = (Join-Path (Split-Path $PSScriptRoot) 'target/fragpicker-server-0.1.0-SNAPSHOT.jar'),
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$previous = @{}
function Need([string]$Name) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))) { throw "Missing environment variable: $Name" }
}
function Enabled([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $false }
    if ($value -notin @('true','false')) { throw "Expected true or false: $Name" }
    return $value -eq 'true'
}
try {
    if (Test-Path -LiteralPath $EnvFile) {
        $seen = @{}
        foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $EnvFile).Path)) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
            if ($line -notmatch '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') { throw 'Invalid environment file assignment; values were not logged' }
            $name = $Matches[1]; $value = $Matches[2].Trim()
            if ($name -notmatch '^(DB_|JWT_|PARSEVIDEO_|INGESTION_|OSS_|TINGWU_|ALIBABA_CLOUD_|AI_CHAT_|KNOWLEDGE_|DIGEST_|MEDIA_|INDEX_|SEARCH_|CHAT_|SCHEDULER_|SERVER_PORT$)') {
                throw "Unsupported application variable: $name"
            }
            if ($seen.ContainsKey($name)) { throw "Duplicate environment variable: $name" }; $seen[$name] = $true
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
                $previous[$name] = [Environment]::GetEnvironmentVariable($name)
                [Environment]::SetEnvironmentVariable($name, $value)
            }
        }
    }
    foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','JWT_SIGNING_KEY')) { Need $name }
    if (-not $env:DB_URL.StartsWith('jdbc:mysql://')) { throw 'DB_URL must use jdbc:mysql' }
    try { $keyBytes = [Convert]::FromBase64String($env:JWT_SIGNING_KEY) } catch { throw 'JWT_SIGNING_KEY must be Base64' }
    if ($keyBytes.Length -lt 32) { throw 'JWT_SIGNING_KEY requires at least 32 random bytes' }
    foreach ($name in @('AI_CHAT_ENABLED','PARSEVIDEO_ENABLED','OSS_ENABLED','TINGWU_ENABLED','INGESTION_WORKER_ENABLED',
            'MEDIA_WORKER_ENABLED','TRANSCRIPTION_WORKER_ENABLED','KNOWLEDGE_ENRICHMENT_ENABLED','KNOWLEDGE_INDEX_ENABLED',
            'DIGEST_WORKER_ENABLED','DIGEST_SCHEDULE_ENABLED','MEDIA_CLEANUP_ENABLED')) { $null = Enabled $name }
    if (Enabled 'AI_CHAT_ENABLED') { Need 'AI_CHAT_API_KEY'; Need 'AI_CHAT_MODEL' }
    if (Enabled 'PARSEVIDEO_ENABLED') { Need 'PARSEVIDEO_BASE_URL' }
    if ((Enabled 'OSS_ENABLED') -or (Enabled 'TINGWU_ENABLED')) {
        Need 'ALIBABA_CLOUD_ACCESS_KEY_ID'; Need 'ALIBABA_CLOUD_ACCESS_KEY_SECRET'
    }
    if (Enabled 'OSS_ENABLED') { Need 'OSS_BUCKET'; Need 'OSS_ENDPOINT' }
    if (Enabled 'TINGWU_ENABLED') { Need 'TINGWU_APP_KEY' }
    $dependencies = @{
        INGESTION_WORKER_ENABLED = @('PARSEVIDEO_ENABLED')
        MEDIA_WORKER_ENABLED = @('PARSEVIDEO_ENABLED','OSS_ENABLED')
        TRANSCRIPTION_WORKER_ENABLED = @('OSS_ENABLED','TINGWU_ENABLED')
        KNOWLEDGE_ENRICHMENT_ENABLED = @('AI_CHAT_ENABLED')
        DIGEST_WORKER_ENABLED = @('AI_CHAT_ENABLED')
        DIGEST_SCHEDULE_ENABLED = @('DIGEST_WORKER_ENABLED')
        MEDIA_CLEANUP_ENABLED = @('OSS_ENABLED')
    }
    foreach ($worker in $dependencies.Keys) {
        if (Enabled $worker) { foreach ($required in $dependencies[$worker]) {
            if (-not (Enabled $required)) { throw "$worker requires $required=true" }
        } }
    }
    if ($Check) { Write-Output 'Application configuration validated; database/cloud connectivity was not checked.'; return }
    $resolvedJar = (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).Path
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must point to JDK 21' }
    Write-Output 'Starting FragPicker with the database profile. Press Ctrl+C to stop.'
    & $java '-jar' $resolvedJar '--spring.profiles.active=database'
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
}
