$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalPath = Join-Path $ProjectRoot '.local'
New-Item -ItemType Directory -Path $LocalPath -Force | Out-Null
function Read-ProjectEnv {
    $envFile = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile)) { Copy-Item -LiteralPath (Join-Path $ProjectRoot '.env.example') -Destination $envFile }
    $allowed = @('DEEPSEEK_API_KEY','DEEPSEEK_BASE_URL','DEEPSEEK_MODEL','DEEPSEEK_TIMEOUT_SECONDS','DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','SERVER_PORT')
    foreach ($line in [IO.File]::ReadAllLines($envFile, [Text.Encoding]::UTF8)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $parts = $text -split '=', 2
        if ($parts.Count -ne 2) { continue }
        $key = $parts[0].Trim(); $value = $parts[1].Trim()
        if ($allowed -contains $key) {
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
            if ($value -or -not [Environment]::GetEnvironmentVariable($key)) { [Environment]::SetEnvironmentVariable($key, $value, 'Process') }
        }
    }
}
function Test-LocalPort([int]$Port) {
    $socket = New-Object Net.Sockets.TcpClient
    try { $task = $socket.ConnectAsync('127.0.0.1', $Port); return ($task.Wait(500) -and $socket.Connected) } catch { return $false } finally { $socket.Dispose() }
}
function Find-MySqlBin([string]$Requested) {
    if ($Requested -and (Test-Path -LiteralPath (Join-Path $Requested 'mysqld.exe'))) { return $Requested }
    $mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($mysqlCommand) { return Split-Path -Parent $mysqlCommand.Source }
    $candidate = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
    if (Test-Path -LiteralPath (Join-Path $candidate 'mysqld.exe')) { return $candidate }
    throw 'MySQL binaries were not found. Install MySQL 8.0.16+ / 8.4, or pass -MySqlBin.'
}
function New-LocalPassword {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Get-ProjectJavaProcesses {
    $jarPath = Join-Path $ProjectRoot 'backend\target\student-manager-1.0.0.jar'
    $jarPattern = '(?i)(?:^|\s)-jar\s+"?' + [regex]::Escape($jarPath) + '"?(?:\s|$)'
    return @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        $normalizedCommand = $_.CommandLine -replace '\\{2,}', '\'
        $normalizedCommand -match $jarPattern
    })
}
