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
function Open-RealtimeSocket($channel,$token){
 $socket=[Net.WebSockets.ClientWebSocket]::new()
 $socket.Options.SetRequestHeader('Authorization',"Bearer $token")
 $timeout=[Threading.CancellationTokenSource]::new(15000)
 try{$null=$socket.ConnectAsync([Uri]("ws://127.0.0.1:8200/ws/"+$channel+"?reliable=true"),$timeout.Token).GetAwaiter().GetResult()}finally{$timeout.Dispose()}
 return $socket
}
function Send-RealtimeFrame($socket,$frame){
 $bytes=[Text.Encoding]::UTF8.GetBytes(($frame|ConvertTo-Json -Compress))
 $timeout=[Threading.CancellationTokenSource]::new(15000)
 try{$null=$socket.SendAsync([ArraySegment[byte]]::new($bytes),[Net.WebSockets.WebSocketMessageType]::Text,$true,$timeout.Token).GetAwaiter().GetResult()}finally{$timeout.Dispose()}
}
function Receive-RealtimeFrame($socket){
 $timeout=[Threading.CancellationTokenSource]::new(15000)
 $stream=[IO.MemoryStream]::new()
 try{
  do{
   $bytes=[byte[]]::new(8192)
   $result=$socket.ReceiveAsync([ArraySegment[byte]]::new($bytes),$timeout.Token).GetAwaiter().GetResult()
   if($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close){throw 'Unexpected WebSocket closure'}
   $stream.Write($bytes,0,$result.Count)
  }while(-not $result.EndOfMessage)
  return ([Text.Encoding]::UTF8.GetString($stream.ToArray())|ConvertFrom-Json)
 }finally{$timeout.Dispose();$stream.Dispose()}
}
function Wait-RealtimeFrame($socket,$type,$ackOther=$true){
 for($i=0;$i -lt 100;$i++){
  $frame=Receive-RealtimeFrame $socket
  if($frame.type -eq $type){return $frame}
  if($ackOther -and $frame.event_id -and $frame.type -ne 'ack.confirmed'){Send-RealtimeFrame $socket @{type='ack';event_id=$frame.event_id}}
 }
 throw "Expected frame missing: $type"
}


$sockets=[Collections.Generic.List[Net.WebSockets.ClientWebSocket]]::new()
$a=$null;$b=$null;$admin=$null
try{
 $null=Invoke-RealtimeApi GET '/health/ready'
 $local=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
 $admin=Invoke-RealtimeApi POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $a=New-RealtimeUser;$b=New-RealtimeUser
 $request=Invoke-RealtimeApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
 $null=Invoke-RealtimeApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
 $conversations=@(Invoke-RealtimeApi GET '/conversations/' $null $a.access_token);$conversation=$conversations[0].id;$path='/conversations/'+$conversation
 $media=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/ffmpeg-install.json')|ConvertFrom-Json
 $gifPath=Join-Path $javaProjectRoot '.tools/attachment-smoke.gif'
 & $media.ffmpeg -v error -nostdin -y -f lavfi -i 'testsrc=size=64x48:rate=2' -t 1 -threads 1 $gifPath
 if($LASTEXITCODE -ne 0){throw 'Synthetic GIF generation failed'}
 $items=@(
  @{type='image';kind='gif';mime='image/gif';name='synthetic.gif';bytes=[IO.File]::ReadAllBytes($gifPath)},
  @{type='file';kind=$null;mime='text/html';name='synthetic.html';bytes=[Text.Encoding]::UTF8.GetBytes('<html>Synthetic attachment; must download</html>')}
 )
 $receiver=Open-RealtimeSocket 'messages' $b.access_token;$sockets.Add($receiver);$null=Wait-RealtimeFrame $receiver 'realtime.ready'
 foreach($item in $items){
  $client=[Net.Http.HttpClient]::new();$form=[Net.Http.MultipartFormDataContent]::new()
  $content=[Net.Http.ByteArrayContent]::new([byte[]]$item.bytes);$content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new($item.mime)
  $form.Add([Net.Http.StringContent]::new($item.type),'media_type');$form.Add([Net.Http.StringContent]::new('chat'),'source');$form.Add([Net.Http.StringContent]::new($conversation),'conversation_id');$form.Add($content,'file',$item.name)
  $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$a.access_token)
  try{$response=$client.PostAsync(($javaBase+'/media/upload'),$form).GetAwaiter().GetResult()
   try{if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)};$asset=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json}
   finally{$response.Dispose()}
  }finally{$form.Dispose();$client.Dispose()}
  if($asset.status -ne 'review_pending'){throw 'Unexpected upload state'}
  $payload=@{type=$item.type;content='synthetic attachment';media_asset_id=$asset.id;client_message_id=('smoke-attachment-'+$item.type)}
  if($item.kind){$payload.media_kind=$item.kind}
  try{$null=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token;throw 'Pending attachment accepted'}
  catch{if([int]$_.Exception.Response.StatusCode -ne 409){throw}}
  $null=Invoke-RealtimeApi POST ("/admin/moderation/media/"+$asset.id+"/approve") @{reason='Synthetic attachment smoke'} $admin.access_token
  $sent=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token;$event=Wait-RealtimeFrame $receiver 'message.created'
  if($event.message.media_asset_id -ne $asset.id -or $event.message.type -ne $item.type){throw 'WebSocket attachment mismatch'}
  if($item.kind -and $event.message.media_kind -ne $item.kind){throw 'GIF kind mismatch'}
  Send-RealtimeFrame $receiver @{type='ack';event_id=$event.event_id};$null=Wait-RealtimeFrame $receiver 'ack.confirmed'
  $retry=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token;if($retry.id -ne $sent.id){throw 'Retry not idempotent'}
  $grantA=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url") $null $a.access_token
  $grantB=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url") $null $b.access_token
  $download=Invoke-WebRequest ($javaBase+$grantB.url) -TimeoutSec 15
  if($item.type -eq 'file'){
   if($download.Headers['Content-Type'] -notcontains 'application/octet-stream' -or $download.Headers['Content-Disposition'][0] -notlike 'attachment;*'){throw 'File must be an attachment'}
   if([Convert]::ToBase64String([byte[]]$download.Content) -ne [Convert]::ToBase64String([byte[]]$item.bytes)){throw 'File bytes changed'}
  }else{
   if($download.Headers['Content-Type'] -notcontains 'image/gif'){throw 'GIF MIME mismatch'}
   $downloadPath=Join-Path $javaProjectRoot '.tools/attachment-smoke-normalized.gif';[IO.File]::WriteAllBytes($downloadPath,[byte[]]$download.Content)
   $frames=& $media.ffprobe -v error -count_frames -show_entries stream=nb_read_frames -of default=nw=1:nk=1 $downloadPath
   if($LASTEXITCODE -ne 0 -or [int]$frames -lt 2){throw 'Animation lost frames'}
  }
  $partial=Invoke-WebRequest ($javaBase+$grantB.url) -Headers @{Range='bytes=0-7'} -TimeoutSec 15
  if($partial.StatusCode -ne 206 -or $partial.RawContentLength -ne 8){throw 'Attachment range failed'}
  $null=Invoke-RealtimeApi DELETE ($path+'/messages/'+$sent.id) $null $b.access_token
  try{$null=Invoke-WebRequest ($javaBase+$grantB.url);throw 'Hidden attachment readable'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
  $null=Invoke-WebRequest ($javaBase+$grantA.url)
  $null=Invoke-RealtimeApi POST ($path+'/messages/'+$sent.id+'/recall') $null $a.access_token
  try{$null=Invoke-WebRequest ($javaBase+$grantA.url);throw 'Recalled attachment readable'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
  $null=Invoke-RealtimeApi DELETE ("/media/"+$asset.id) $null $a.access_token
 }
 $report=@{checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;schema='V14';result='passed'
  checks=@('animated_gif_upload_and_frames','opaque_file_byte_integrity','forced_download','pending_send_rejected','manual_review','gif_and_file_websocket','ack','idempotent_retry','range_206','hide_and_recall_revoke_links','media_deletion')
  note='Synthetic local data only. Real malware scanning, cloud storage, asynchronous workers and Flutter device integration remain pending.'}
 [IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-attachments-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 foreach($socket in $sockets){try{$socket.Abort();$socket.Dispose()}catch{}}
 if($a){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$a.refresh_token}}
 if($b){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$b.refresh_token}}
 if($admin){$null=Invoke-RealtimeApi POST '/admin/auth/logout' $null $admin.access_token}
}
