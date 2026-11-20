#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$ConfigDirectory='D:\WebApps\MobileTasksConfig',[string]$PoolName='MobileTasksApi',[string]$ExpectedProject='mobile-tasks-in-1c')
$ErrorActionPreference='Stop'
$taskConfigPath=Join-Path $ConfigDirectory 'database.json'
$taskServicePath=Join-Path $ConfigDirectory 'firebase-service-account.json'
if(!(Test-Path -LiteralPath $taskConfigPath)){throw 'Configure the database first.'}
$taskAccount=Get-Content -LiteralPath $taskServicePath -Raw | ConvertFrom-Json
if($taskAccount.type -ne 'service_account' -or !$taskAccount.private_key -or !$taskAccount.client_email){throw 'Expected a Firebase service account JSON.'}
if($taskAccount.project_id -ne $ExpectedProject){throw 'Service account project does not match the Android Firebase project.'}
$taskConfig=Get-Content -LiteralPath $taskConfigPath -Raw | ConvertFrom-Json
$taskConfig | Add-Member -MemberType NoteProperty -Name Push -Value @{ServiceAccountFile=$taskServicePath} -Force
[IO.File]::WriteAllText($taskConfigPath,($taskConfig | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
foreach($taskPath in @($taskConfigPath,$taskServicePath)){
 & icacls.exe $taskPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' "IIS AppPool\${PoolName}:R" | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Could not set config ACL.'}
}
$taskAccount=$null
Write-Host 'Push configured. Recycle MobileTasksApi. Check http://127.0.0.1:8088/health/push'
