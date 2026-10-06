param([string]$MySqlBin)

$ErrorActionPreference = 'Stop'
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
foreach ($name in @('DB_TEST_URL','DB_TEST_USERNAME','DB_TEST_PASSWORD','MYSQL_PWD','JWT_SIGNING_KEY')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$testProcess = $null
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
    Write-Output "Running integration tests on isolated MySQL at localhost:$port. Existing MySQL service is untouched."
    & (Join-Path $serverRoot 'mvnw.cmd') '-B' '-ntp' '-f' (Join-Path $serverRoot 'pom.xml') 'verify'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL integration verification failed' }
} finally {
    if ($testProcess -and -not $testProcess.HasExited) {
        & $mysqladmin '--no-defaults' '--host=127.0.0.1' "--port=$port" '--user=root' 'shutdown' *> $null
        if (-not $testProcess.WaitForExit(5000)) { Stop-Process -Id $testProcess.Id }
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
    }
}
