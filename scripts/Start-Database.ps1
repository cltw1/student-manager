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
# Project folders can be moved after MySQL is initialized. Rebase only the
# generated local data/log/PID paths before starting this project's server.
$dataPath = Join-Path $dbPath 'data'
if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'auto.cnf'))) { throw 'Project MySQL data was not found in .local/mysql/data. Check the moved project directory before starting.' }
$iniText = [IO.File]::ReadAllText($iniPath, [Text.Encoding]::UTF8)
$updatedIni = $iniText
$pathSettings = [ordered]@{
    'datadir' = $dataPath
    'log-error' = Join-Path $dbPath 'mysql-error.log'
    'pid-file' = Join-Path $dbPath 'mysqld.pid'
}
foreach ($setting in $pathSettings.GetEnumerator()) {
    $pattern = '(?m)^[\t ]*' + [regex]::Escape($setting.Key) + '[\t ]*=[^\r\n]*'
    if ([regex]::Matches($updatedIni, $pattern).Count -ne 1) { throw "Project MySQL configuration must contain exactly one $($setting.Key) entry. Check .local/mysql/my.ini." }
    $value = $setting.Key + '=' + $setting.Value.Replace('\', '/')
    $updatedIni = [regex]::Replace($updatedIni, $pattern, [Text.RegularExpressions.MatchEvaluator]{ param($match) $value })
}
if ($updatedIni -cne $iniText) {
    $backupPath = Join-Path $dbPath ('my.ini.before-path-update.' + [Guid]::NewGuid().ToString('N') + '.bak')
    Copy-Item -LiteralPath $iniPath -Destination $backupPath
    [IO.File]::WriteAllText($iniPath, $updatedIni, (New-Object Text.UTF8Encoding $false))
    Write-Host 'Updated project MySQL paths for the current folder; previous configuration was backed up.'
}
$binPath = [IO.File]::ReadAllText((Join-Path $dbPath 'bin-path.txt'), [Text.Encoding]::UTF8).Trim()
$databaseProcess = Start-Process -FilePath (Join-Path $binPath 'mysqld.exe') -ArgumentList @("--defaults-file=`"$iniPath`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dbPath 'server.stdout.log') -RedirectStandardError (Join-Path $dbPath 'server.stderr.log')
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    if ($databaseProcess.HasExited) { throw 'MySQL exited during startup. See .local/mysql/mysql-error.log.' }
    if (Test-LocalPort 3307) { Write-Host 'Project MySQL is running on 127.0.0.1:3307.'; return }
}
throw 'MySQL startup timed out. See .local/mysql/mysql-error.log.'
