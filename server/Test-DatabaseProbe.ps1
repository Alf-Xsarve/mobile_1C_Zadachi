# Developer integration check: requires an ISOLATED PostgreSQL fixture with
# role mobile_tasks_api and password test;quote"slash\ (not production).
# No tables or databases are changed by these tests. Run with PowerShell 7.
param(
    [Parameter(Mandatory=$true)][string]$Dotnet,
    [int]$PostgresPort = 15439,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($PostgresPort -eq 5432) { throw 'Use an isolated test PostgreSQL, not the default production port.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskDll = Join-Path $PSScriptRoot 'publish\MobileTasks.Api.dll'
$taskConfigPath = Join-Path $OutputDirectory 'database-test.json'
$taskPreviousConfig = $env:MOBILETASKS_CONFIG_FILE
$taskTestPassword = 'test;quote"slash\'
$taskSettings = @{
    Host = '127.0.0.1'; Port = $PostgresPort; Name = 'postgres'
    Username = 'mobile_tasks_api'; Password = $taskTestPassword
}
$taskCases = @(
    @{ Name='missing-config'; Http=503; Code='database_not_configured'; Config=@{} },
    @{ Name='invalid-port'; Http=503; Code='database_configuration_invalid'; Config=@{ Database=($taskSettings + @{}) } },
    @{ Name='connected'; Http=200; Code=$null; Config=@{ Database=($taskSettings + @{}) } },
    @{ Name='wrong-password'; Http=503; Code='database_authentication_failed'; Config=@{ Database=($taskSettings + @{}) } },
    @{ Name='missing-database'; Http=503; Code='database_not_found'; Config=@{ Database=($taskSettings + @{}) } },
    @{ Name='connection-refused'; Http=503; Code='database_connection_failed'; Config=@{ Database=($taskSettings + @{}) } }
)
$taskCases[1].Config.Database.Port = 'bad'
$taskCases[3].Config.Database.Password = 'incorrect-test-password'
$taskCases[4].Config.Database.Name = 'mobiletasks_missing_test_database'
$taskCases[5].Config.Database.Port = 1
$taskPort = 18110
try {
    $env:MOBILETASKS_CONFIG_FILE = $taskConfigPath
    foreach ($taskCase in $taskCases) {
        [IO.File]::WriteAllText($taskConfigPath, ($taskCase.Config | ConvertTo-Json -Depth 3))
        $taskLog = Join-Path $OutputDirectory ($taskCase.Name + '.log')
        $taskErrorLog = Join-Path $OutputDirectory ($taskCase.Name + '.error.log')
        $taskProcess = Start-Process -FilePath $Dotnet -ArgumentList $taskDll,'--urls',"http://127.0.0.1:$taskPort" -WindowStyle Hidden -PassThru -RedirectStandardOutput $taskLog -RedirectStandardError $taskErrorLog
        try {
            $taskLive = $null
            for ($taskAttempt=0; $taskAttempt -lt 15; $taskAttempt++) {
                if ($taskProcess.HasExited) { throw "API exited: $($taskCase.Name)" }
                try { $taskLive = Invoke-RestMethod "http://127.0.0.1:$taskPort/health/live" -TimeoutSec 1 -NoProxy; break }
                catch { Start-Sleep -Milliseconds 300 }
            }
            if ($taskLive.status -ne 'ok') { throw 'Liveness failed' }
            $taskReply = Invoke-WebRequest "http://127.0.0.1:$taskPort/health/database" -TimeoutSec 12 -NoProxy -SkipHttpErrorCheck
            $taskJson = $taskReply.Content | ConvertFrom-Json
            if ([int]$taskReply.StatusCode -ne $taskCase.Http -or $taskJson.code -ne $taskCase.Code) {
                throw "Unexpected response for $($taskCase.Name): $($taskReply.StatusCode) $($taskReply.Content)"
            }
            if ($taskCase.Http -eq 200 -and $taskJson.database -ne 'connected') { throw 'Database not connected' }
            if ($taskReply.Headers['Cache-Control'] -ne 'no-store') { throw 'Diagnostic response must not be cached' }
            if ($taskReply.Content.Contains($taskTestPassword)) { throw 'Password leaked in response' }
            Write-Host "PASS $($taskCase.Name): HTTP $($taskReply.StatusCode)"
        }
        finally {
            if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id; $taskProcess.WaitForExit() }
        }
        foreach ($taskLogFile in @($taskLog,$taskErrorLog)) {
            if (([IO.File]::ReadAllText($taskLogFile)).Contains($taskTestPassword)) { throw 'Password leaked in logs' }
        }
        $taskPort++
    }
}
finally { $env:MOBILETASKS_CONFIG_FILE = $taskPreviousConfig }
Write-Host 'PASS all 6 database integration scenarios; liveness and secret redaction checked in each.'
