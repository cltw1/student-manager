param([switch]$SkipBuild, [switch]$OpenBrowser)
. (Join-Path $PSScriptRoot 'Common.ps1')
Read-ProjectEnv
$serverPort = 8080
if ($env:SERVER_PORT) { $serverPort = [int]$env:SERVER_PORT }
if (Test-LocalPort $serverPort) { throw "Port $serverPort is occupied. Stop the running app first." }
if ($env:DB_PORT -eq '3307' -and $env:DB_HOST -eq '127.0.0.1' -and (Test-Path -LiteralPath (Join-Path $LocalPath 'mysql\my.ini'))) { & (Join-Path $PSScriptRoot 'Start-Database.ps1') }
if ($env:DB_PORT -eq '3307' -and $env:DB_HOST -eq '127.0.0.1' -and (Test-Path -LiteralPath (Join-Path $LocalPath 'mysql\root-client.cnf')) -and -not (Test-Path -LiteralPath (Join-Path $LocalPath 'schema-permissions.enabled'))) { & (Join-Path $PSScriptRoot 'Enable-SchemaChanges.ps1') }
$jar = Join-Path $ProjectRoot 'backend\target\student-manager-1.0.0.jar'
if (-not $SkipBuild -or -not (Test-Path -LiteralPath $jar)) { & (Join-Path $PSScriptRoot 'Build.ps1') }
$javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
if ($env:JAVA_HOME) {
    $javaHomeCommand = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $javaHomeCommand) { $javaCommand = $javaHomeCommand }
}
$appProcess = Start-Process -FilePath $javaCommand -ArgumentList @('-Dfile.encoding=UTF-8','-jar',"`"$jar`"") -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LocalPath 'backend.stdout.log') -RedirectStandardError (Join-Path $LocalPath 'backend.stderr.log')
[IO.File]::WriteAllText((Join-Path $LocalPath 'backend.pid'), [string]$appProcess.Id)
$url = "http://127.0.0.1:$serverPort"
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    if ($appProcess.HasExited) { throw 'Backend exited. See .local/backend.stdout.log and backend.stderr.log.' }
    if (Test-LocalPort $serverPort) {
        Write-Host "Application started: $url"
        if (-not $env:DEEPSEEK_API_KEY) { Write-Host 'DeepSeek API Key is empty. Manual student management is available; set .env and restart to enable AI.' }
        if ($OpenBrowser) { Start-Process $url -WindowStyle Hidden }
        return
    }
}
throw 'Application startup timed out. Check .local/backend.stdout.log.'
