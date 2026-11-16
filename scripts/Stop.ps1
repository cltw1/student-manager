param([switch]$KeepDatabase)
. (Join-Path $PSScriptRoot 'Common.ps1')
$pidFile = Join-Path $LocalPath 'backend.pid'
$ownedProcesses = Get-ProjectJavaProcesses
foreach ($ownedProcess in $ownedProcesses) {
    Stop-Process -Id $ownedProcess.ProcessId -Force
    Wait-Process -Id $ownedProcess.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    if (Get-Process -Id $ownedProcess.ProcessId -ErrorAction SilentlyContinue) { throw 'Project Java process did not stop.' }
    Write-Host 'Project backend stopped.'
}
if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile -Force }
$clientFile = Join-Path $LocalPath 'mysql\root-client.cnf'
if (-not $KeepDatabase -and (Test-Path -LiteralPath $clientFile) -and (Test-LocalPort 3307)) {
    $dbPath = Join-Path $LocalPath 'mysql'
    $iniPath = Join-Path $dbPath 'my.ini'
    $ownedId = [int]([IO.File]::ReadAllText((Join-Path $dbPath 'mysqld.pid')).Trim())
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedId" -ErrorAction SilentlyContinue
    if (-not $owned -or $owned.CommandLine -notlike "*$iniPath*") { throw 'Database ownership check failed. Nothing was stopped.' }
    $binPath = [IO.File]::ReadAllText((Join-Path $dbPath 'bin-path.txt'), [Text.Encoding]::UTF8).Trim()
    & (Join-Path $binPath 'mysqladmin.exe') "--defaults-file=$clientFile" shutdown
    if ($LASTEXITCODE -ne 0) { throw 'Project database shutdown failed.' }
    Write-Host 'Project database stopped. Student data was preserved.'
}
