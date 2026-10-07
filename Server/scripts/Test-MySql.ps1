param([string]$MySqlBin, [switch]$AndroidAuth, [int]$HttpPort = 0, [switch]$Gradle, [switch]$AndroidChatLive, [switch]$AndroidKnowledgeFixture, [switch]$AndroidDigestLive,
    [string]$AndroidGradleInitScript, [string]$AndroidTestClass, [switch]$AndroidRetryFixture)

$ErrorActionPreference = 'Stop'
if ($AndroidChatLive -and (-not $AndroidAuth -or [string]::IsNullOrWhiteSpace($env:AI_CHAT_API_KEY))) {
    throw 'AndroidChatLive requires AndroidAuth and AI_CHAT_API_KEY in the process environment'
}
if ($AndroidKnowledgeFixture -and -not $AndroidAuth) { throw 'AndroidKnowledgeFixture requires AndroidAuth' }
if ($AndroidRetryFixture -and -not $AndroidAuth) { throw 'AndroidRetryFixture requires AndroidAuth' }
if ($AndroidDigestLive -and (-not $AndroidChatLive -or -not $AndroidKnowledgeFixture)) { throw 'AndroidDigestLive requires AndroidChatLive and AndroidKnowledgeFixture' }
if (-not $MySqlBin) {
    $MySqlBin = Split-Path (Get-Command mysqld.exe -ErrorAction Stop).Source
}
$serverRoot = Split-Path $PSScriptRoot
$workspaceRoot = Split-Path $serverRoot
$testRoot = Join-Path $workspaceRoot ('.tools/mysql-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$dataPath = Join-Path $testRoot 'data'
$mysqlBase = Split-Path $MySqlBin
$mysqld = Join-Path $MySqlBin 'mysqld.exe'
$mysql = Join-Path $MySqlBin 'mysql.exe'
$mysqladmin = Join-Path $MySqlBin 'mysqladmin.exe'
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$savedEnvironment = @{}
foreach ($name in @('DB_TEST_URL','DB_TEST_USERNAME','DB_TEST_PASSWORD','MYSQL_PWD','JWT_SIGNING_KEY',
        'DB_URL','DB_USERNAME','DB_PASSWORD','SERVER_PORT','AI_CHAT_ENABLED','ANDROID_KNOWLEDGE_FIXTURE','ANDROID_RETRY_FIXTURE','DIGEST_WORKER_ENABLED','DIGEST_SCHEDULE_ENABLED')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$testProcess = $null
$apiProcess = $null
try {
    $env:MYSQL_PWD = $null
    & $mysqld '--no-defaults' '--initialize-insecure' "--basedir=$mysqlBase" "--datadir=$dataPath" '--console' *> (Join-Path $testRoot 'initialize.log')
    if ($LASTEXITCODE -ne 0) { throw "Test MySQL initialization failed; see $testRoot/initialize.log" }
    $arguments = @('--no-defaults', "`"--basedir=$mysqlBase`"", "`"--datadir=$dataPath`"",
        '--bind-address=127.0.0.1', "--port=$port", '--mysqlx=0', '--skip-log-bin', '--console')
    $testProcess = Start-Process -FilePath $mysqld -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testRoot 'stdout.log') -RedirectStandardError (Join-Path $testRoot 'stderr.log')
    $ready = $false
    for ($attempt=0; $attempt -lt 80; $attempt++) {
        & $mysqladmin '--no-defaults' '--host=127.0.0.1' "--port=$port" '--user=root' 'ping' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        if ($testProcess.HasExited) { throw "Test MySQL exited; see $testRoot/stderr.log" }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Test MySQL did not become ready' }
    $testPassword = [guid]::NewGuid().ToString('N')
    $setupSql = "CREATE DATABASE fragpicker_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; ALTER USER 'root'@'localhost' IDENTIFIED BY '$testPassword';"
    & $mysql '--no-defaults' '--host=127.0.0.1' "--port=$port" '--user=root' "--execute=$setupSql"
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure isolated test database' }
    $env:MYSQL_PWD = $testPassword
    $env:DB_TEST_URL = "jdbc:mysql://127.0.0.1:$port/fragpicker_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED"
    $env:DB_TEST_USERNAME = 'root'
    $env:DB_TEST_PASSWORD = $testPassword
    $env:JWT_SIGNING_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:ANDROID_KNOWLEDGE_FIXTURE = if ($AndroidKnowledgeFixture) { 'true' } else { 'false' }
    $env:ANDROID_RETRY_FIXTURE = if ($AndroidRetryFixture) { 'true' } else { 'false' }
    $env:DIGEST_WORKER_ENABLED = 'false'
    $env:DIGEST_SCHEDULE_ENABLED = 'false'
    Write-Output "Running integration tests on isolated MySQL at localhost:$port. Existing MySQL service is untouched."
    if ($Gradle) {
        & (Join-Path $serverRoot 'gradlew.bat') '-p' $serverRoot 'clean' 'build' '--no-daemon' '--console=plain'
    } else {
        & (Join-Path $serverRoot 'mvnw.cmd') '-B' '-ntp' '-f' (Join-Path $serverRoot 'pom.xml') 'verify'
    }
    if ($LASTEXITCODE -ne 0) { throw 'MySQL integration verification failed' }
    if ($AndroidAuth) {
        $httpListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $HttpPort)
        $httpListener.Start()
        $HttpPort = $httpListener.LocalEndpoint.Port
        $httpListener.Stop()
        if ($HttpPort -lt 1024 -or $HttpPort -gt 65535) { throw 'HttpPort must be 1024 to 65535' }
        $env:DB_URL = $env:DB_TEST_URL
        $env:DB_USERNAME = $env:DB_TEST_USERNAME
        $env:DB_PASSWORD = $env:DB_TEST_PASSWORD
        $env:SERVER_PORT = "$HttpPort"
        $env:AI_CHAT_ENABLED = if ($AndroidChatLive) { 'true' } else { 'false' }
        $env:DIGEST_WORKER_ENABLED = if ($AndroidDigestLive) { 'true' } else { 'false' }
        $jarDirectory = if ($Gradle) { 'build/libs' } else { 'target' }
        $jar = Join-Path $serverRoot "$jarDirectory/fragpicker-server-0.1.0-SNAPSHOT.jar"
        $javaExe = Join-Path $env:JAVA_HOME 'bin/java.exe'
        $apiProcess = Start-Process -FilePath $javaExe -ArgumentList '-jar', "`"$jar`"",
            '--spring.profiles.active=database', '--server.address=127.0.0.1' -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $testRoot 'api-stdout.log') -RedirectStandardError (Join-Path $testRoot 'api-stderr.log')
        $healthy = $false
        for ($attempt=0; $attempt -lt 120; $attempt++) {
            if ($apiProcess.HasExited) { throw "Test backend exited; see $testRoot/api-stderr.log" }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/actuator/health" -TimeoutSec 1
                if ($health.status -eq 'UP') { $healthy = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 250
        }
        if (-not $healthy) { throw 'Isolated backend did not become healthy' }
        Write-Output 'Running Android login integration against the isolated real backend and database.'
        $androidArguments = @('-p', (Join-Path $workspaceRoot 'Android'),
            "-PAPI_BASE_URL=http://10.0.2.2:$HttpPort", '-Pandroid.testInstrumentationRunnerArguments.realBackend=true',
            ':app:assembleDebug', ':app:lintDebug', ':app:connectedDebugAndroidTest')
        if ($AndroidGradleInitScript) { $androidArguments += @('-I', (Resolve-Path -LiteralPath $AndroidGradleInitScript).Path) }
        if ($AndroidTestClass) { $androidArguments += "-Pandroid.testInstrumentationRunnerArguments.class=$AndroidTestClass" }
        if ($AndroidChatLive) { $androidArguments += '-Pandroid.testInstrumentationRunnerArguments.chatLive=true' }
        if ($AndroidKnowledgeFixture) { $androidArguments += '-Pandroid.testInstrumentationRunnerArguments.knowledgeFixture=true' }
        if ($AndroidRetryFixture) { $androidArguments += '-Pandroid.testInstrumentationRunnerArguments.retryFixture=true' }
        if ($AndroidDigestLive) { $androidArguments += '-Pandroid.testInstrumentationRunnerArguments.digestLive=true' }
        & (Join-Path $workspaceRoot 'Android/gradlew.bat') @androidArguments
        if ($LASTEXITCODE -ne 0) { throw 'Android authentication verification failed' }
    }
} finally {
    if ($apiProcess -and -not $apiProcess.HasExited) { Stop-Process -Id $apiProcess.Id }
    if ($testProcess -and -not $testProcess.HasExited) {
        & $mysqladmin '--no-defaults' '--host=127.0.0.1' "--port=$port" '--user=root' 'shutdown' *> $null
        if (-not $testProcess.WaitForExit(5000)) { Stop-Process -Id $testProcess.Id }
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
    }
}
