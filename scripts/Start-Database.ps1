. (Join-Path $PSScriptRoot 'Common.ps1')
$dbPath = Join-Path $LocalPath 'mysql'
$iniPath = Join-Path $dbPath 'my.ini'
if (-not (Test-Path -LiteralPath $iniPath)) { throw 'Run scripts/Initialize-Database.ps1 first, or configure an existing MySQL in .env.' }
if (Test-LocalPort 3307) {
    $pidPath = Join-Path $dbPath 'mysqld.pid'
    if (-not (Test-Path -LiteralPath $pidPath)) { throw 'Port 3307 is occupied by another process.' }
    $ownedId = [int]([IO.File]::ReadAllText($pidPath).Trim())
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedId" -ErrorAction SilentlyContinue
    if (-not $owned -or $owned.CommandLine -notlike "*$iniPath*") { throw 'Port 3307 could not be verified as this project database.' }
    return
}
$binPath = [IO.File]::ReadAllText((Join-Path $dbPath 'bin-path.txt'), [Text.Encoding]::UTF8).Trim()
$databaseProcess = Start-Process -FilePath (Join-Path $binPath 'mysqld.exe') -ArgumentList @("--defaults-file=`"$iniPath`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dbPath 'server.stdout.log') -RedirectStandardError (Join-Path $dbPath 'server.stderr.log')
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    if ($databaseProcess.HasExited) { throw 'MySQL exited during startup. See .local/mysql/mysql-error.log.' }
    if (Test-LocalPort 3307) { Write-Host 'Project MySQL is running on 127.0.0.1:3307.'; return }
}
throw 'MySQL startup timed out. See .local/mysql/mysql-error.log.'
