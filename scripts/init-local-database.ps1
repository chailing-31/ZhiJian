param([Parameter(Mandatory=$true)][string]$CredentialsFile)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$mysql = (Get-Command mysql.exe -ErrorAction Stop).Source
$lines = Get-Content -LiteralPath $CredentialsFile
$rootPassword = ($lines | Where-Object { $_.StartsWith('Root password: ') }) -replace '^Root password: ', ''
if (-not $rootPassword) { throw 'Root password is missing from the local credentials file.' }
$info = New-Object Diagnostics.ProcessStartInfo
$info.FileName = $mysql
$info.Arguments = '--no-defaults --no-login-paths --default-character-set=utf8mb4 --host=127.0.0.1 --user=root --batch'
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$info.EnvironmentVariables['MYSQL_PWD'] = $rootPassword
$process = [Diagnostics.Process]::Start($info)
try {
    $files = @('database/tables.sql', 'database/demo.sql', 'database/migrations/20261001_A3_inspection_integration.sql', 'database/migrations/20261012_C_business_integration.sql')
    foreach ($file in $files) {
        $absolute = Join-Path $project $file
        if (-not (Test-Path -LiteralPath $absolute)) { throw "Missing migration: $file" }
        $bytes = [Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($absolute, [Text.Encoding]::UTF8) + "`n")
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes("SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='smart_fresh_demo'; SELECT batch_code FROM smart_fresh_demo.batches;`n")
    $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(60000)) { $process.Kill(); throw 'Database initialization timed out.' }
    if ($process.ExitCode -ne 0 -or $stderr.Result -match '(?m)^ERROR') { throw ('Database initialization failed: ' + $stderr.Result.Replace($rootPassword,'[REDACTED]')) }
    $stdout.Result
    'DATABASE INITIALIZATION PASSED; existing records were not deleted.'
} finally { $process.Dispose() }
