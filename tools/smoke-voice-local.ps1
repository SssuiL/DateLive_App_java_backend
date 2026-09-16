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
 $javaLocal=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
 $admin=Invoke-RealtimeApi POST '/admin/auth/login' @{username=$javaLocal.adminUsername;password=$javaLocal.adminPassword}
 $a=New-RealtimeUser
 $b=New-RealtimeUser
 $request=Invoke-RealtimeApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
 $null=Invoke-RealtimeApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
 $conversations=@(Invoke-RealtimeApi GET '/conversations/' $null $a.access_token)
 $conversation=$conversations[0].id
 $path='/conversations/'+$conversation
 $audio=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/ffmpeg-install.json')|ConvertFrom-Json
 $audioPath=Join-Path $javaProjectRoot '.tools/voice-smoke.m4a'
 & $audio.ffmpeg -v error -nostdin -y -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 1.25 -c:a aac -ac 1 -movflags +faststart $audioPath
 if($LASTEXITCODE -ne 0){throw 'Synthetic AAC generation failed'}
 $voiceBytes=[IO.File]::ReadAllBytes($audioPath)

 $client=[Net.Http.HttpClient]::new()
 $form=[Net.Http.MultipartFormDataContent]::new()
 $voiceContent=[Net.Http.ByteArrayContent]::new($voiceBytes)
 $voiceContent.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('audio/mp4')
 $form.Add([Net.Http.StringContent]::new('voice'),'media_type')
 $form.Add([Net.Http.StringContent]::new('chat'),'source')
 $form.Add([Net.Http.StringContent]::new($conversation),'conversation_id')
 $form.Add($voiceContent,'file','synthetic-chat-voice.m4a')
 $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$a.access_token)
 try{
  $response=$client.PostAsync(($javaBase+'/media/upload'),$form).GetAwaiter().GetResult()
  try{
   if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)}
   $asset=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
  }finally{$response.Dispose()}
 }finally{$form.Dispose();$client.Dispose()}
 if($asset.status -ne 'review_pending' -or $asset.conversation_id -ne $conversation){throw 'Invalid voice upload state'}
 $payload=@{type='voice';content='[语音]';duration_seconds=60;media_asset_id=$asset.id;client_message_id='chat-voice-smoke'}
 try{$null=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token;throw 'Pending voice accepted'}
 catch{if([int]$_.Exception.Response.StatusCode -ne 409){throw}}
 $queue=@(Invoke-RealtimeApi GET ("/admin/moderation/media?source=chat&conversation_id="+$conversation) $null $admin.access_token)
 if($queue.Count -ne 1 -or $queue[0].id -ne $asset.id){throw 'Admin source/conversation filters failed'}
 $null=Invoke-RealtimeApi POST ("/admin/moderation/media/"+$asset.id+"/approve") @{reason='合成语音本地验证'} $admin.access_token
 $receiver=Open-RealtimeSocket 'messages' $b.access_token
 $sockets.Add($receiver)
 $null=Wait-RealtimeFrame $receiver 'realtime.ready'
 $sent=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token
 $event=Wait-RealtimeFrame $receiver 'message.created'
 if($sent.duration_seconds -ne 2 -or $event.message.duration_seconds -ne 2){throw 'Client duration was trusted instead of decoded duration'}
 if($event.message.media_asset_id -ne $asset.id -or $event.message.type -ne 'voice'){throw 'WebSocket attachment mismatch'}
 Send-RealtimeFrame $receiver @{type='ack';event_id=$event.event_id}
 $null=Wait-RealtimeFrame $receiver 'ack.confirmed'
 $retry=Invoke-RealtimeApi POST ($path+'/messages') $payload $a.access_token
 if($retry.id -ne $sent.id){throw 'Voice retry was not idempotent'}
 $grantA=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url") $null $a.access_token
 $grantB=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url") $null $b.access_token
 $playback=Invoke-WebRequest ($javaBase+$grantB.url) -TimeoutSec 15
 if($playback.Headers['Content-Type'] -notcontains 'audio/wav'){throw 'Audio MIME mismatch'}
 $null=Invoke-RealtimeApi DELETE ($path+'/messages/'+$sent.id) $null $b.access_token
 try{$null=Invoke-WebRequest ($javaBase+$grantB.url) -TimeoutSec 15;throw 'Hidden voice still readable'}
 catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 $null=Invoke-WebRequest ($javaBase+$grantA.url) -TimeoutSec 15
 $recall=Invoke-RealtimeApi POST ($path+'/messages/'+$sent.id+'/recall') $null $a.access_token
 if($recall.media_asset_id -or $recall.media_kind -or $recall.duration_seconds -ne 0){throw 'Recalled voice fields not masked'}
 try{$null=Invoke-WebRequest ($javaBase+$grantA.url) -TimeoutSec 15;throw 'Recalled voice still readable'}
 catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 $null=Invoke-RealtimeApi DELETE ("/media/"+$asset.id) $null $a.access_token
 $report=@{
  checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed';schema='V12'
  checks=@('aac_lc_upload_and_decode','pending_send_rejected','admin_chat_filters','manual_approval','voice_send_server_duration','websocket_voice_delivery','ack','idempotent_retry','private_wav_playback','personal_hide_revokes_old_url','other_member_access_preserved','recall_masks_attachment_and_revokes_url','media_deletion')
  note='Synthetic AAC tone and accounts only; independent Java database. Server-decoded WAV playback verified; no real speech moderation, Flutter changes, video/GIF support or forced account erasure.'
 }
 [IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-voice-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 foreach($socket in $sockets){try{$socket.Abort();$socket.Dispose()}catch{}}
 if($a){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$a.refresh_token}}
 if($b){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$b.refresh_token}}
 if($admin){$null=Invoke-RealtimeApi POST '/admin/auth/logout' $null $admin.access_token}
}
