$ErrorActionPreference='Stop'
$workerRoot=Split-Path $PSScriptRoot -Parent
$workerRuntime=Join-Path $workerRoot '.tools/jdk/jdk-25.0.4.1+1/bin/java.exe'
$workerJar=Join-Path $workerRoot '.tools/run/media-worker.jar'
$workerPidPath=Join-Path $workerRoot '.tools/run/media-worker.pid'
if(Test-Path -LiteralPath $workerPidPath){
 $recorded=[int](Get-Content -LiteralPath $workerPidPath)
 if(Get-Process -Id $recorded -ErrorAction SilentlyContinue){throw 'Media worker already running; stop it before replacing the JAR.'}
}
$config=Get-Content -Raw (Join-Path $workerRoot '.tools/local-development.json')|ConvertFrom-Json
$audio=Get-Content -Raw (Join-Path $workerRoot '.tools/ffmpeg-install.json')|ConvertFrom-Json
$workerEnv=@{
 JAVA_DATABASE_PASSWORD=$config.databasePassword;JAVA_DATABASE_USER='manliao_java'
 JAVA_DATABASE_URL='jdbc:postgresql://127.0.0.1:15433/manliao_java';JAVA_JWT_SECRET=$config.jwtSecret
 JAVA_SMS_CODE_SECRET=$config.smsCodeSecret;JAVA_MEDIA_FFMPEG=$audio.ffmpeg;JAVA_MEDIA_FFPROBE=$audio.ffprobe
}
$previous=@{}
try{
 foreach($entry in $workerEnv.GetEnumerator()){$previous[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process');[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
 [IO.Directory]::CreateDirectory((Split-Path $workerJar -Parent))|Out-Null
 Copy-Item -LiteralPath (Join-Path $workerRoot 'target/backend-0.0.1-SNAPSHOT.jar') -Destination $workerJar -Force
 $process=Start-Process -FilePath $workerRuntime -ArgumentList @('-Xmx256m','-XX:ActiveProcessorCount=2','-jar',('"'+$workerJar+'"'),'--spring.profiles.active=media-worker') -WorkingDirectory $workerRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workerRoot '.tools/run/media-worker.log') -RedirectStandardError (Join-Path $workerRoot '.tools/run/media-worker-error.log')
 [IO.File]::WriteAllText($workerPidPath,[string]$process.Id)
 $deadline=[DateTime]::UtcNow.AddSeconds(25);$ready=$false
 while([DateTime]::UtcNow -lt $deadline){
  if($process.HasExited){Copy-Item -LiteralPath (Join-Path $workerRoot '.tools/run/media-worker.log') -Destination (Join-Path $workerRoot '.tools/worker-start-failure.log') -Force;throw 'Media worker exited; inspect .tools/run/media-worker logs'}
  $log=Get-Content -Raw (Join-Path $workerRoot '.tools/run/media-worker.log') -ErrorAction SilentlyContinue
  if($log -match 'Started ManliaoBackendApplication'){$ready=$true;break}
  Start-Sleep -Milliseconds 250
 }
 if(-not $ready){throw 'Media worker startup timeout'}
 Write-Host 'Independent media worker ready (no HTTP listener; heap capped at 256 MiB).'
}finally{foreach($entry in $previous.GetEnumerator()){if($null -eq $entry.Value){Remove-Item -LiteralPath ('Env:'+$entry.Key) -ErrorAction SilentlyContinue}else{[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}}}
