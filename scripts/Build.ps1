param([switch]$SkipTests)
. (Join-Path $PSScriptRoot 'Common.ps1')
if ((Get-ProjectJavaProcesses).Count -gt 0) { throw 'This project is running. Run scripts/Stop.ps1 -KeepDatabase before rebuilding.' }
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
$mavenCommand = (Get-Command mvn.cmd -ErrorAction Stop).Source
Push-Location (Join-Path $ProjectRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & $npmCommand ci --registry=https://registry.npmjs.org "--cache=$(Join-Path $ProjectRoot 'work\npm-cache')" --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & $npmCommand run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$staticPath = Join-Path $ProjectRoot 'backend\src\main\resources\static'
New-Item -ItemType Directory -Path $staticPath -Force | Out-Null
Copy-Item -Path (Join-Path $ProjectRoot 'frontend\dist\*') -Destination $staticPath -Recurse -Force
$settings = Join-Path $PSScriptRoot 'maven-settings.xml'
Push-Location (Join-Path $ProjectRoot 'backend')
try {
    $mavenArguments = @('-B','-gs',$settings,'-s',$settings,"-Dmaven.repo.local=$(Join-Path $ProjectRoot 'work\maven-repository')",'-Dstyle.color=never','clean','package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & $mavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
} finally { Pop-Location }
Write-Host 'Build complete: backend/target/student-manager-1.0.0.jar'
