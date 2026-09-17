param([switch]$DevelopmentSms,[switch]$DevelopmentAdmin,[switch]$DevelopmentBilling,[switch]$DisableAccountErasure)
$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaRuntime=Join-Path $javaProjectRoot '.tools/jdk/jdk-25.0.4.1+1/bin/java.exe'
$javaJar=Join-Path $javaProjectRoot 'target/backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $javaJar)) { throw 'Build first: tools/maven.ps1 verify' }
$javaLocalSettings=Join-Path $javaProjectRoot '.tools/local-development.json'
if (-not (Test-Path $javaLocalSettings)) {
  $javaLocalConfig=@{
    databasePassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    jwtSecret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
  }
  [IO.File]::WriteAllText($javaLocalSettings,($javaLocalConfig|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
$javaLocalConfig=Get-Content -Raw $javaLocalSettings | ConvertFrom-Json -AsHashtable
if(-not $javaLocalConfig.ContainsKey('smsCodeSecret')) {
  $javaLocalConfig.smsCodeSecret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
  [IO.File]::WriteAllText($javaLocalSettings,($javaLocalConfig|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if($DevelopmentAdmin -and -not $javaLocalConfig.ContainsKey('adminPassword')) {
 $javaLocalConfig.adminUsername='dev_owner'
 $javaLocalConfig.adminPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 [IO.File]::WriteAllText($javaLocalSettings,($javaLocalConfig|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
$javaPreviousEnv=@{}
$javaLocalEnv=@{
 JAVA_DATABASE_PASSWORD=$javaLocalConfig.databasePassword
 JAVA_DATABASE_USER='manliao_java'
 JAVA_DATABASE_URL='jdbc:postgresql://127.0.0.1:15433/manliao_java'
 JAVA_JWT_SECRET=$javaLocalConfig.jwtSecret
 JAVA_ADMIN_BOOTSTRAP_ENABLED=$(if($DevelopmentAdmin){'true'}else{'false'})
 JAVA_ADMIN_BOOTSTRAP_USERNAME=$javaLocalConfig.adminUsername
 JAVA_ADMIN_BOOTSTRAP_PASSWORD=$javaLocalConfig.adminPassword
 JAVA_SMS_CODE_SECRET=$javaLocalConfig.smsCodeSecret
 JAVA_SMS_PROVIDER=$(if($DevelopmentSms){'development'}else{'disabled'})
 JAVA_SMS_RETURN_DEV_CODE=$(if($DevelopmentSms){'true'}else{'false'})
 JAVA_LEGACY_REGISTRATION_ENABLED='true'
 JAVA_API_PORT='8200'
 JAVA_APP_ENV='development'
 JAVA_DEV_RECHARGE_ENABLED=$(if($DevelopmentBilling){'true'}else{'false'})
 JAVA_API_BIND='127.0.0.1'
}
if($javaLocalConfig.groupPublicBaseUrl -and -not $env:JAVA_GROUP_PUBLIC_BASE_URL){
 $javaLocalEnv.JAVA_GROUP_PUBLIC_BASE_URL=$javaLocalConfig.groupPublicBaseUrl
}
$audioManifest=Join-Path $javaProjectRoot '.tools/ffmpeg-install.json'
if(Test-Path $audioManifest){
 $audio=Get-Content -Raw $audioManifest|ConvertFrom-Json
 $javaLocalEnv.JAVA_MEDIA_FFMPEG=$audio.ffmpeg
 $javaLocalEnv.JAVA_MEDIA_FFPROBE=$audio.ffprobe
}
Push-Location $javaProjectRoot
try {
 foreach($entry in $javaLocalEnv.GetEnumerator()) {
   $javaPreviousEnv[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process')
   [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')
 }
 docker compose -f compose.yaml up -d --wait postgres
 if($LASTEXITCODE -ne 0) { throw 'Java-only PostgreSQL startup failed' }
 if($DevelopmentBilling){Write-Host 'LOCAL ONLY: simulated coin recharge enabled; no real payment.'}
 if($DevelopmentAdmin){Write-Host 'LOCAL ONLY: first admin initialization enabled; credentials stored in .tools/local-development.json and never printed.'}
 if($DevelopmentSms){Write-Host 'LOCAL ONLY: development SMS enabled; API returns test codes and sends no real messages.'}
 Write-Host 'Java development API: http://127.0.0.1:8200 (password-only test registration enabled)'
 $javaRunDir=Join-Path $javaProjectRoot '.tools/run'
 [IO.Directory]::CreateDirectory($javaRunDir)|Out-Null
 $javaPidFile=Join-Path $javaRunDir 'api.pid'
 if(Test-Path $javaPidFile) {
   $javaOldPid=[int](Get-Content $javaPidFile)
   if(Get-Process -Id $javaOldPid -ErrorAction SilentlyContinue){throw 'API process recorded as running. Use tools/stop-local.ps1 first.'}
 }
 $javaRunJar=Join-Path $javaRunDir 'backend.jar'
 Copy-Item -LiteralPath $javaJar -Destination $javaRunJar -Force
 $javaErasureArgument='--app.accounts.worker-enabled='+$(if($DisableAccountErasure){'false'}else{'true'})
 Write-Host ('Account erasure worker enabled: '+(-not $DisableAccountErasure))
 $javaProcess=Start-Process -FilePath $javaRuntime -ArgumentList @('-Xmx256m','-jar',('"'+$javaRunJar+'"'),$javaErasureArgument,'--spring.profiles.active=media-api') -WorkingDirectory $javaProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $javaRunDir 'api.log') -RedirectStandardError (Join-Path $javaRunDir 'api-error.log')
 [IO.File]::WriteAllText($javaPidFile,[string]$javaProcess.Id)
 $javaReady=$false
 $javaDeadline=[DateTime]::UtcNow.AddSeconds(25)
 while([DateTime]::UtcNow -lt $javaDeadline) {
  if($javaProcess.HasExited){throw 'API exited; inspect .tools/run logs'}
  try {
   $javaHealth=Invoke-RestMethod 'http://127.0.0.1:8200/health/ready' -TimeoutSec 2
   if($javaHealth.status -eq 'ready'){$javaReady=$true;break}
  } catch {}
  Start-Sleep -Milliseconds 250
 }
 if(-not $javaReady){throw 'Readiness timeout; inspect .tools/run logs'}
 Write-Host 'Java API ready: http://127.0.0.1:8200; stop with tools/stop-local.ps1'
 if($env:JAVA_MEDIA_JOBS_WORKER_ENABLED -ne 'false'){
  & (Join-Path $PSScriptRoot 'start-media-worker-local.ps1')
 }
 $javaStartExit=0
} finally {
 foreach($entry in $javaPreviousEnv.GetEnumerator()) {
   if($null -eq $entry.Value){Remove-Item -LiteralPath ('Env:'+$entry.Key) -ErrorAction SilentlyContinue}
   else{[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
 }
 Pop-Location
}
exit $javaStartExit
