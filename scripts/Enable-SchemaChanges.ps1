. (Join-Path $PSScriptRoot 'Common.ps1')
Read-ProjectEnv
if ($env:DB_HOST -ne '127.0.0.1' -or $env:DB_PORT -ne '3307') { throw 'For an external MySQL, ask its administrator to grant schema permissions using docs/REVIEW.md.' }
if ($env:DB_NAME -notmatch '^[A-Za-z][A-Za-z0-9_]*$' -or $env:DB_USERNAME -notmatch '^[A-Za-z][A-Za-z0-9_]*$') { throw 'Database name or username does not match the supported local configuration.' }
$clientFile = Join-Path $LocalPath 'mysql\root-client.cnf'
if (-not (Test-Path -LiteralPath $clientFile)) { throw 'Project database administrator configuration was not found.' }
$binPath = [IO.File]::ReadAllText((Join-Path $LocalPath 'mysql\bin-path.txt'), [Text.Encoding]::UTF8).Trim()
$sql = "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, LOCK TABLES, TRIGGER ON ``$($env:DB_NAME)``.* TO '$($env:DB_USERNAME)'@'127.0.0.1';`nGRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, LOCK TABLES, TRIGGER ON ``$($env:DB_NAME)``.* TO '$($env:DB_USERNAME)'@'localhost';"
$oldPwd = $env:MYSQL_PWD; $env:MYSQL_PWD = $null
try { $sql | & (Join-Path $binPath 'mysql.exe') "--defaults-file=$clientFile" --batch; if ($LASTEXITCODE -ne 0) { throw 'Could not grant schema permissions.' } }
finally { $env:MYSQL_PWD = $oldPwd }
[IO.File]::WriteAllText((Join-Path $LocalPath 'schema-permissions.enabled'), 'enabled')
Write-Host 'Local schema changes are enabled for this project database only. Existing student values were unchanged.'
