param([string]$MySqlBin)
. (Join-Path $PSScriptRoot 'Common.ps1')
Read-ProjectEnv
if ($env:DB_PORT -ne '3307' -or $env:DB_HOST -ne '127.0.0.1' -or $env:DB_NAME -ne 'student_manager' -or $env:DB_USERNAME -ne 'student_app') {
    throw 'This helper creates the isolated database at 127.0.0.1:3307 only. For an existing MySQL, import database/schema.sql and configure .env manually.'
}
$binPath = Find-MySqlBin $MySqlBin
$dbPath = Join-Path $LocalPath 'mysql'
$dataPath = Join-Path $dbPath 'data'
$iniPath = Join-Path $dbPath 'my.ini'
$clientPath = Join-Path $dbPath 'root-client.cnf'
if (Test-Path -LiteralPath $clientPath) { & (Join-Path $PSScriptRoot 'Start-Database.ps1'); Write-Host 'Existing project database is ready.'; exit 0 }
if ((Test-Path -LiteralPath $dataPath) -and (Get-ChildItem -LiteralPath $dataPath -Force | Select-Object -First 1)) { throw 'Database directory already contains files. Initialization stopped to preserve existing data.' }
if (Test-LocalPort 3307) { throw 'Port 3307 is occupied. No existing database was changed.' }
New-Item -ItemType Directory -Path $dataPath -Force | Out-Null
$basePath = (Split-Path -Parent $binPath).Replace('\','/')
$dataIni = $dataPath.Replace('\','/')
$errorIni = (Join-Path $dbPath 'mysql-error.log').Replace('\','/')
$pidIni = (Join-Path $dbPath 'mysqld.pid').Replace('\','/')
$iniText = "[mysqld]`nbasedir=$basePath`ndatadir=$dataIni`nport=3307`nbind-address=127.0.0.1`nmysqlx=OFF`ncharacter-set-server=utf8mb4`ncollation-server=utf8mb4_0900_ai_ci`nlog-error=$errorIni`npid-file=$pidIni`n"
[IO.File]::WriteAllText($iniPath, $iniText, [Text.Encoding]::ASCII)
[IO.File]::WriteAllText((Join-Path $dbPath 'bin-path.txt'), $binPath, [Text.Encoding]::UTF8)
Write-Host 'Initializing a separate MySQL data directory. Existing MySQL services are unchanged.'
$init = Start-Process -FilePath (Join-Path $binPath 'mysqld.exe') -ArgumentList @('--no-defaults','--initialize-insecure',"--basedir=`"$basePath`"", "--datadir=`"$dataIni`"") -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $dbPath 'initialize.stdout.log') -RedirectStandardError (Join-Path $dbPath 'initialize.stderr.log')
if ($init.ExitCode -ne 0) { throw 'MySQL initialization failed. See .local/mysql/initialize.stderr.log.' }
& (Join-Path $PSScriptRoot 'Start-Database.ps1')
$rootPassword = New-LocalPassword
$appPassword = New-LocalPassword
$schema = [IO.File]::ReadAllText((Join-Path $ProjectRoot 'database\schema.sql'), [Text.Encoding]::UTF8)
$sql = $schema + "`nCREATE USER IF NOT EXISTS 'student_app'@'127.0.0.1' IDENTIFIED BY '$appPassword';`nCREATE USER IF NOT EXISTS 'student_app'@'localhost' IDENTIFIED BY '$appPassword';`nGRANT SELECT, INSERT, UPDATE, DELETE ON student_manager.* TO 'student_app'@'127.0.0.1';`nGRANT SELECT, INSERT, UPDATE, DELETE ON student_manager.* TO 'student_app'@'localhost';`nALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';`n"
$oldPwd = $env:MYSQL_PWD; $env:MYSQL_PWD = $null
try { $sql | & (Join-Path $binPath 'mysql.exe') --no-defaults --protocol=tcp --host=127.0.0.1 --port=3307 --user=root --skip-password --default-character-set=utf8mb4 --batch; if ($LASTEXITCODE -ne 0) { throw 'Database bootstrap failed.' } }
finally { $env:MYSQL_PWD = $oldPwd }
[IO.File]::WriteAllText($clientPath, "[client]`nhost=127.0.0.1`nport=3307`nuser=root`npassword=$rootPassword`nprotocol=tcp`n", [Text.Encoding]::ASCII)
$envFile = Join-Path $ProjectRoot '.env'
$envText = [IO.File]::ReadAllText($envFile, [Text.Encoding]::UTF8)
if ($envText -match '(?m)^DB_PASSWORD=') { $envText = [regex]::Replace($envText, '(?m)^DB_PASSWORD=.*$', ('DB_PASSWORD=' + $appPassword)) }
else { $envText += "`nDB_PASSWORD=$appPassword`n" }
[IO.File]::WriteAllText($envFile, $envText, (New-Object Text.UTF8Encoding $false))
Write-Host 'Database ready at 127.0.0.1:3307. Credentials were saved locally without printing them.'
Write-Host 'Next: fill DEEPSEEK_API_KEY in .env and run scripts/Start.ps1.'
