#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$ConfigDirectory = 'D:\WebApps\MobileTasksConfig',
    [string]$PoolName = 'MobileTasksApi'
)
$ErrorActionPreference = 'Stop'

# Run interactively ON THE SERVER. No password is embedded in this script or
# passed via command-line arguments. Windows PowerShell 5.1 is sufficient.
$taskDatabaseSecret = Read-Host 'Password for mobile_tasks_api in PostgreSQL' -AsSecureString
if ($taskDatabaseSecret.Length -eq 0) { throw 'Password must not be empty.' }
$taskConfigPath = Join-Path $ConfigDirectory 'database.json'
New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null

# Native ACL tool avoids a dependency on Get-Acl/Set-Acl PowerShell modules.
# Administrators/SYSTEM: full control. Only this app pool gets read access.
& icacls.exe $ConfigDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "IIS AppPool\${PoolName}:(OI)(CI)RX" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the config directory. Check the app pool name.' }

$taskPlainPassword = [System.Net.NetworkCredential]::new('', $taskDatabaseSecret).Password
try {
    $taskSettings = @{
        Database = @{
            Host = '127.0.0.1'
            Port = 5432
            Name = 'mobile_tasks'
            Username = 'mobile_tasks_api'
            Password = $taskPlainPassword
        }
    } | ConvertTo-Json -Depth 3
    [System.IO.File]::WriteAllText($taskConfigPath, $taskSettings, [System.Text.UTF8Encoding]::new($false))
    & icacls.exe $taskConfigPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' "IIS AppPool\${PoolName}:R" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the database config file.' }
}
finally {
    $taskPlainPassword = $null
    $taskSettings = $null
    $taskDatabaseSecret.Dispose()
}
Write-Host "Database settings saved outside the website: $taskConfigPath"
Write-Host 'Recycle only the MobileTasksApi application pool, then open http://127.0.0.1:8088/health/database on this server.'
