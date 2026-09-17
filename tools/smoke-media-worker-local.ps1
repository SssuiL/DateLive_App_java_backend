$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-RealtimeApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 $response=Invoke-RestMethod @requestArgs
 foreach($item in $response){$item}
}
function New-RealtimeUser{
 $phone='196'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
 Invoke-RealtimeApi POST '/auth/register' @{phone=$phone;password=('Realtime-'+[Guid]::NewGuid().ToString('N'));nickname='实时迁移测试'}
}

function Upload-DerivativeFixture($bytes,$mime,$conversation,$token,$type="image"){
 $client=[Net.Http.HttpClient]::new();$form=[Net.Http.MultipartFormDataContent]::new()
 $content=[Net.Http.ByteArrayContent]::new([byte[]]$bytes);$content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new($mime)
 $form.Add([Net.Http.StringContent]::new($type),'media_type');$form.Add([Net.Http.StringContent]::new('chat'),'source');$form.Add([Net.Http.StringContent]::new($conversation),'conversation_id');$form.Add($content,'file','synthetic-image')
 $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
 try{$response=$client.PostAsync(($javaBase+'/media/upload?async=true'),$form).GetAwaiter().GetResult()
  try{if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)};return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json)}
  finally{$response.Dispose()}
 }finally{$form.Dispose();$client.Dispose()}
}
function Wait-MediaResult($id,$token){
 $deadline=[DateTime]::UtcNow.AddSeconds(45)
 do{
  $result=Invoke-RealtimeApi GET ('/media/'+$id) $null $token
  if($result.processing_status -in @('ready','failed')){return $result}
  Start-Sleep -Milliseconds 250
 }while([DateTime]::UtcNow -lt $deadline)
 throw 'Media processing timed out'
}
$a=$null;$b=$null;$admin=$null;$workerPaused=$false
$previousWorker=$env:JAVA_MEDIA_JOBS_WORKER_ENABLED
try{
 & (Join-Path $PSScriptRoot 'stop-local.ps1')
 $env:JAVA_MEDIA_JOBS_WORKER_ENABLED='false';$workerPaused=$true
 & (Join-Path $PSScriptRoot 'start-local.ps1') -DevelopmentSms -DevelopmentAdmin
 if($LASTEXITCODE -ne 0){throw 'Start with paused media worker failed'}
 $local=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
 $admin=Invoke-RealtimeApi POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $a=New-RealtimeUser;$b=New-RealtimeUser
 $request=Invoke-RealtimeApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
 $null=Invoke-RealtimeApi POST ('/friends/requests/'+$request.id+'/accept') $null $b.access_token
 $conversations=@(Invoke-RealtimeApi GET '/conversations/' $null $a.access_token);$conversation=$conversations[0].id
 $media=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/ffmpeg-install.json')|ConvertFrom-Json
 $pngPath=Join-Path $javaProjectRoot '.tools/jobs-smoke.png';$videoPath=Join-Path $javaProjectRoot '.tools/jobs-smoke.mp4'
 & $media.ffmpeg -v error -nostdin -y -f lavfi -i 'testsrc=size=640x480:rate=1' -frames:v 1 -threads 1 $pngPath
 if($LASTEXITCODE -ne 0){throw 'PNG fixture failed'}
 & $media.ffmpeg -v error -nostdin -y -f lavfi -i 'color=c=blue:s=64x48:r=24' -t 1 -c:v libx264 -threads 1 $videoPath
 if($LASTEXITCODE -ne 0){throw 'Video fixture failed'}
 $image=Upload-DerivativeFixture ([IO.File]::ReadAllBytes($pngPath)) 'image/png' $conversation $a.access_token
 $video=Upload-DerivativeFixture ([IO.File]::ReadAllBytes($videoPath)) 'video/mp4' $conversation $a.access_token 'video'
 $bad=Upload-DerivativeFixture ([byte[]]@(1,2,3)) 'image/png' $conversation $a.access_token
 $cancelled=Upload-DerivativeFixture ([IO.File]::ReadAllBytes($pngPath)) 'image/png' $conversation $a.access_token
 foreach($asset in @($image,$video,$bad,$cancelled)){
  if($asset.processing_status -ne 'queued' -or $asset.preview_url){throw 'Expected unprocessed durable task'}
 }
 try{$null=Invoke-RealtimeApi GET ('/media/'+$image.id) $null $b.access_token;throw 'Peer status exposed'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 try{$null=Invoke-RealtimeApi POST ('/media/'+$image.id+'/access-url') $null $a.access_token;throw 'Raw media exposed'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 $null=Invoke-RealtimeApi DELETE ('/media/'+$cancelled.id) $null $a.access_token
 $apiPidBefore=[int](Get-Content (Join-Path $javaProjectRoot '.tools/run/api.pid'))
 $env:JAVA_MEDIA_JOBS_WORKER_ENABLED='true'
 & (Join-Path $PSScriptRoot 'start-media-worker-local.ps1')
 if($LASTEXITCODE -ne 0){throw 'Independent media worker start failed'}
 $workerPid=[int](Get-Content (Join-Path $javaProjectRoot '.tools/run/media-worker.pid'))
 if($workerPid -eq $apiPidBefore){throw 'Worker shares API process'}
 if(Get-NetTCPConnection -OwningProcess $workerPid -State Listen -ErrorAction SilentlyContinue){throw 'Worker unexpectedly listens on TCP'}
 $workerPaused=$false
 foreach($asset in @($image,$video)){
  $ready=Wait-MediaResult $asset.id $a.access_token
  if($ready.processing_status -ne 'ready' -or $ready.status -ne 'review_pending'){throw 'Restart did not process valid task'}
  $null=Invoke-RealtimeApi POST ('/admin/moderation/media/'+$asset.id+'/approve') @{reason='Synthetic durable job smoke'} $admin.access_token
  $kind=if($asset.id -eq $image.id){'image'}else{'video'}
  $message=@{type=$kind;media_asset_id=$asset.id;client_message_id=('jobs-smoke-'+$kind);content='synthetic queued media'}
  if($kind -eq 'image'){$message.media_kind='image'}
  $sent=Invoke-RealtimeApi POST ('/conversations/'+$conversation+'/messages') $message $a.access_token
  $grant=Invoke-RealtimeApi POST ('/media/'+$asset.id+'/access-url?variant=thumbnail') $null $b.access_token
  $output=Join-Path $javaProjectRoot ('.tools/jobs-download-'+$kind+'.png')
  Invoke-WebRequest ($javaBase+$grant.url) -OutFile $output -TimeoutSec 15
  $width=& $media.ffprobe -v error -show_entries stream=width -of default=nw=1:nk=1 $output
  $expected=if($kind -eq 'image'){320}else{64}
  if($LASTEXITCODE -ne 0 -or [int]$width -ne $expected){throw 'Processed thumbnail invalid'}
  $partial=Invoke-WebRequest ($javaBase+$grant.url) -Headers @{Range='bytes=0-7'} -TimeoutSec 15
  if($partial.StatusCode -ne 206){throw 'Processed range failed'}
  $null=Invoke-RealtimeApi DELETE ('/conversations/'+$conversation+'/messages/'+$sent.id) $null $b.access_token
  try{$null=Invoke-WebRequest ($javaBase+$grant.url);throw 'Hidden processed media accessible'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
  $null=Invoke-RealtimeApi DELETE ('/media/'+$asset.id) $null $a.access_token
 }
 $failed=Wait-MediaResult $bad.id $a.access_token
 if($failed.processing_status -ne 'failed' -or $failed.processing_error -ne 'MEDIA_TYPE_INVALID'){throw 'Invalid media did not fail safely'}
 $null=Invoke-RealtimeApi DELETE ('/media/'+$bad.id) $null $a.access_token
 try{$null=Invoke-RealtimeApi GET ('/media/'+$cancelled.id) $null $a.access_token;throw 'Cancelled task resurrected'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 foreach($asset in @($image,$video,$bad,$cancelled)){foreach($extension in @('.png','.thumb.png','.display.png','.mp4')){
  if(Test-Path -LiteralPath (Join-Path $javaProjectRoot ('.data/media/'+$asset.id+$extension))){throw 'Deleted async output remains'}
 }}
 if([int](Get-Content (Join-Path $javaProjectRoot '.tools/run/api.pid')) -ne $apiPidBefore){throw 'API process changed during worker processing'}
 & (Join-Path $PSScriptRoot 'stop-media-worker-local.ps1')
 $health=Invoke-RealtimeApi GET '/health/ready';if($health.status -ne 'ready'){throw 'Stopping worker disrupted API'}
 & (Join-Path $PSScriptRoot 'start-media-worker-local.ps1')
 $report=@{checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;schema='V16';result='passed'
  checks=@('queued_without_download','owner_only_status','cancel_queued_task','separate_worker_without_api_restart','image_and_video_processed','malformed_input_failed','review_and_message_send','thumbnail_decode','range_206','hide_revokes_access','bundle_deletion','worker_has_no_tcp_listener','worker_stop_keeps_api_ready')
  note='Synthetic Java-local data. Draft expiry/reference races, quotas, rollback, retries, and erasure verified separately in isolated integration tests. No production capacity claim or Flutter cutover.'}
 [IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-media-worker-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 if($workerPaused){
  & (Join-Path $PSScriptRoot 'stop-local.ps1');$env:JAVA_MEDIA_JOBS_WORKER_ENABLED='true'
  & (Join-Path $PSScriptRoot 'start-local.ps1') -DevelopmentSms -DevelopmentAdmin
 }
 $env:JAVA_MEDIA_JOBS_WORKER_ENABLED=$previousWorker
 if($a){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$a.refresh_token}}
 if($b){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$b.refresh_token}}
 if($admin){$null=Invoke-RealtimeApi POST '/admin/auth/logout' $null $admin.access_token}
}
