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
$taskSecretPath=Join-Path $ConfigDirectory 'one-c-webhook-key.txt'
if(Test-Path -LiteralPath $taskSecretPath){$taskSecret=(Get-Content -LiteralPath $taskSecretPath -Raw).Trim()}
else {$taskBytes=New-Object byte[] 32;$taskRandom=[System.Security.Cryptography.RandomNumberGenerator]::Create();try{$taskRandom.GetBytes($taskBytes)}finally{$taskRandom.Dispose()};$taskSecret=[Convert]::ToBase64String($taskBytes);[IO.File]::WriteAllText($taskSecretPath,$taskSecret)}
$taskConfig | Add-Member -MemberType NoteProperty -Name Push -Value @{ServiceAccountFile=$taskServicePath;OneCSecret=$taskSecret} -Force
[IO.File]::WriteAllText($taskConfigPath,($taskConfig | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
foreach($taskPath in @($taskConfigPath,$taskServicePath)){
 & icacls.exe $taskPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' "IIS AppPool\${PoolName}:R" | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Could not set config ACL.'}
}
& icacls.exe $taskSecretPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not set webhook key ACL.'}
$taskSecret=$null;$taskAccount=$null
Write-Host 'Push configured. Recycle MobileTasksApi. Check http://127.0.0.1:8088/health/push'
Write-Host "The 1C webhook key is stored in $taskSecretPath. Keep it on the server."
