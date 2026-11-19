param([switch]$Apply, [string]$Database)
. (Join-Path $PSScriptRoot 'Common.ps1')
Read-ProjectEnv
if ($env:DB_HOST -ne '127.0.0.1' -or $env:DB_PORT -ne '3307') { throw 'This migration supports the project-owned local MySQL on port 3307 only.' }
if (-not $Database) { $Database = $env:DB_NAME }
if ($Database -notmatch '^[A-Za-z][A-Za-z0-9_]*$') { throw 'Invalid database name.' }
if ($Database -ne $env:DB_NAME -and $Database -notmatch '^student_format_test_[a-f0-9]{12}$') { throw 'The selected database is not this project database or an isolated format test database.' }
if ($Apply -and $Database -eq $env:DB_NAME -and (Get-ProjectJavaProcesses).Count -gt 0) { throw 'Stop the project backend with scripts/Stop.ps1 -KeepDatabase before applying the migration.' }
$clientFile = Join-Path $LocalPath 'mysql\root-client.cnf'
$binPath = [IO.File]::ReadAllText((Join-Path $LocalPath 'mysql\bin-path.txt'), [Text.Encoding]::UTF8).Trim()
$mysqlExe = Join-Path $binPath 'mysql.exe'
$clientArgs = @("--defaults-file=$clientFile", '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
function Query-FormatMigration([string]$Sql) {
    $rows = & $mysqlExe @clientArgs "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'Migration database query failed.' }
    return $rows
}
function Quote-FormatIdentifier([string]$Name) { if ($Name -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') { throw 'Unsupported SQL identifier.' }; return ('`' + $Name + '`') }
$dbQuoted = Quote-FormatIdentifier $Database
$table = $dbQuoted + '.student'
$columns = @(); $genderColumns = @()
$genderLabel = [string][char]0x6027 + [char]0x522b
$male = [string][char]0x7537; $female = [string][char]0x5973
foreach ($line in @(Query-FormatMigration "SELECT COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='student' ORDER BY ORDINAL_POSITION;")) {
    $parts = $line -split "`t", 2; $column = $parts[0]; [void](Quote-FormatIdentifier $column); $columns += $column; $label = $column
    if ($parts.Count -gt 1 -and $parts[1].StartsWith('student-field:')) {
        $encoded = $parts[1].Substring(14).Replace('-', '+').Replace('_', '/'); while ($encoded.Length % 4) { $encoded += '=' }
        $label = ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json).label
    }
    if (@($genderLabel, 'gender', 'sex') -contains $label.Normalize([Text.NormalizationForm]::FormKC).Trim().ToLowerInvariant()) { $genderColumns += $column }
}
if ($columns -notcontains 'student_no' -or $columns -notcontains 'name') { throw 'Student core columns were not found.' }
$mapping = @(); $targets = @{}
foreach ($id in @(Query-FormatMigration "SELECT student_no FROM $table ORDER BY student_no;")) {
    $target = if ($id.Length -lt 7) { $id.PadRight(7, '0') } else { $id.Substring(0, 7) }
    if ($target -cnotmatch '^[1-9][0-9]{6}$') { throw "Student number '$id' cannot be normalized to a nonzero seven-digit number without user input." }
    if ($targets.ContainsKey($target)) { throw "Student number collision: '$id' and '$($targets[$target])' both become '$target'. No data was modified." }
    $targets[$target] = $id; $mapping += [pscustomobject]@{before=$id;after=$target;changed=($id -cne $target)}
}
if ([int](@(Query-FormatMigration "SELECT COUNT(*) FROM $table WHERE name IS NULL OR CHAR_LENGTH(name) NOT BETWEEN 1 AND 15 OR CHAR_LENGTH(TRIM(name))=0;")[0]) -gt 0) { throw 'Some existing names exceed 15 characters or are blank. No data was modified.' }
foreach ($column in $genderColumns) {
    $quoted = Quote-FormatIdentifier $column
    if ([int](@(Query-FormatMigration "SELECT COUNT(*) FROM $table WHERE $quoted IS NULL OR CHAR_LENGTH($quoted)<>1 OR $quoted NOT IN ('$male','$female');")[0]) -gt 0) { throw 'Some existing gender values are not male/female. No data was modified.' }
}
$dependencies = [int](@(Query-FormatMigration "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_NAME IS NOT NULL AND ((TABLE_SCHEMA='$Database' AND TABLE_NAME='student') OR (REFERENCED_TABLE_SCHEMA='$Database' AND REFERENCED_TABLE_NAME='student'));")[0])
$triggers = [int](@(Query-FormatMigration "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='$Database' AND EVENT_OBJECT_TABLE='student';")[0])
if ($dependencies -gt 0 -or $triggers -gt 0) { throw 'The table has foreign keys or triggers; automatic format migration is not supported.' }
$mapping | Format-Table before,after,changed
if (-not $Apply) { Write-Output 'Preview only. Use -Apply after stopping the backend to normalize IDs and enable database constraints.'; return }
$token = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$stageName = 'student_format_stage_' + $token; $backupName = 'student_format_backup_' + $token
$stage = $dbQuoted + '.' + (Quote-FormatIdentifier $stageName); $backup = $dbQuoted + '.' + (Quote-FormatIdentifier $backupName)
$backupDirectory = Join-Path $ProjectRoot ('work\format-migration-' + $token)
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
$dumpPath = Join-Path $backupDirectory 'student-before.sql'
& (Join-Path $binPath 'mysqldump.exe') "--defaults-file=$clientFile" --default-character-set=utf8mb4 --single-transaction --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 "--result-file=$dumpPath" $Database student
if ($LASTEXITCODE -ne 0) { throw 'Student backup failed. No data was modified.' }
$columnList = ($columns | ForEach-Object { Quote-FormatIdentifier $_ }) -join ','
$selectList = ($columns | ForEach-Object { if ($_ -eq 'student_no') { "CASE WHEN CHAR_LENGTH(student_no)<7 THEN RPAD(student_no,7,'0') ELSE LEFT(student_no,7) END" } else { Quote-FormatIdentifier $_ } }) -join ','
$statements = @("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';", "CREATE TABLE $stage LIKE $table;", "ALTER TABLE $stage MODIFY COLUMN student_no VARCHAR(7) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, MODIFY COLUMN name VARCHAR(15) CHARACTER SET utf8mb4 NOT NULL;", "ALTER TABLE $stage ADD CONSTRAINT ``fmt_${token}_id`` CHECK (student_no REGEXP '^[1-9][0-9]{6}$'), ADD CONSTRAINT ``fmt_${token}_name`` CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 15 AND CHAR_LENGTH(TRIM(name))>0);")
$index = 0
foreach ($column in $genderColumns) { $quoted = Quote-FormatIdentifier $column; $statements += "ALTER TABLE $stage ADD CONSTRAINT ``fmt_${token}_gender_${index}`` CHECK ($quoted IS NOT NULL AND CHAR_LENGTH($quoted)=1 AND $quoted IN ('$male','$female'));"; $index++ }
$statements += @("LOCK TABLES $table WRITE, $stage WRITE;", "INSERT INTO $stage ($columnList) SELECT $selectList FROM $table;", "RENAME TABLE $table TO $backup, $stage TO $table;", 'UNLOCK TABLES;')
$sql = $statements -join "`n"
[IO.File]::WriteAllText((Join-Path $backupDirectory 'migration.sql'), $sql, (New-Object Text.UTF8Encoding $false))
$previousEncoding = $OutputEncoding; $OutputEncoding = New-Object Text.UTF8Encoding $false
try {
    $sql | & $mysqlExe @clientArgs
    if ($LASTEXITCODE -ne 0) {
        $swapped = [int](@(Query-FormatMigration "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$backupName';")[0]) -gt 0
        if ($swapped) { throw "Table switch completed but a later command failed. Check $table and the preserved backup $backup." }
        [void](Query-FormatMigration "DROP TABLE IF EXISTS $stage;")
        throw 'Format migration failed before the table switch. The original student table is preserved.'
    }
} finally { $OutputEncoding = $previousEncoding }
$count = [int](@(Query-FormatMigration "SELECT COUNT(*) FROM $table;")[0])
$report = @{database=$Database;studentCount=$count;changedCount=@($mapping | Where-Object changed).Count;mapping=$mapping;backupTable=$backupName;backupSql=$dumpPath;genderColumns=$genderColumns}
[IO.File]::WriteAllText((Join-Path $backupDirectory 'result.json'), ($report | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding $false))
Write-Output ("Format migration completed. Students: {0}; renumbered: {1}; backup table: {2}" -f $count,$report.changedCount,$backupName)
Write-Output ('Report: ' + (Join-Path $backupDirectory 'result.json'))
