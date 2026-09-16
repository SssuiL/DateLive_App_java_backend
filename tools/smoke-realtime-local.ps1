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
$a=$null;$b=$null
try{
 $null=Invoke-RealtimeApi GET '/health/ready'
 $a=New-RealtimeUser
 $b=New-RealtimeUser
 $request=Invoke-RealtimeApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
 $null=Invoke-RealtimeApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
 $conversations=@(Invoke-RealtimeApi GET '/conversations/' $null $a.access_token)
 $path='/conversations/'+$conversations[0].id
 $receiver=Open-RealtimeSocket 'messages' $b.access_token
 $sockets.Add($receiver)
 $null=Wait-RealtimeFrame $receiver 'realtime.ready'
 $notifications=Open-RealtimeSocket 'notifications' $b.access_token
 $sockets.Add($notifications)
 $null=Wait-RealtimeFrame $notifications 'realtime.ready'
 $null=Wait-RealtimeFrame $notifications 'notification.unread_count'
 $message=Invoke-RealtimeApi POST ($path+'/messages') @{client_message_id='realtime-smoke';content='Java 自动实时投递测试'} $a.access_token
 $created=Wait-RealtimeFrame $receiver 'message.created'
 if($created.message.id -ne $message.id){throw 'Message identity mismatch'}
 $before=@(Invoke-RealtimeApi GET ($path+'/messages') $null $a.access_token)
 if($before[0].delivered_to_user_ids -contains $b.user_id){throw 'Socket send falsely marked delivered'}
 $receiver.Abort()
 $receiver.Dispose()
 $resumed=Open-RealtimeSocket 'messages' $b.access_token
 $sockets.Add($resumed)
 $null=Wait-RealtimeFrame $resumed 'realtime.ready'
 $replayed=Wait-RealtimeFrame $resumed 'message.created'
 if($replayed.event_id -ne $created.event_id){throw 'Reconnect did not replay the same event'}
 Send-RealtimeFrame $resumed @{type='ack';event_id=$replayed.event_id}
 $confirmed=Wait-RealtimeFrame $resumed 'ack.confirmed'
 if($confirmed.event_id -ne $created.event_id){throw 'ACK confirmation mismatch'}
 $after=@(Invoke-RealtimeApi GET ($path+'/messages') $null $a.access_token)
 if($after[0].delivered_to_user_ids -notcontains $b.user_id){throw 'ACK did not persist delivery receipt'}
 $conversation=Invoke-RealtimeApi GET $path $null $b.access_token
 if($conversation.unread_count -ne 1){throw 'ACK falsely marked message read'}
 $notification=$null
 for($i=0;$i -lt 10;$i++){
  $notification=Wait-RealtimeFrame $notifications 'notification.unread_count'
  if($notification.event_id){Send-RealtimeFrame $notifications @{type='ack';event_id=$notification.event_id}}
  if($notification.unread_count -eq 2){break}
 }
 if($notification.unread_count -ne 2){throw 'Notification count was not synchronized'}
 Send-RealtimeFrame $resumed @{type='ping'}
 $null=Wait-RealtimeFrame $resumed 'pong'
 $report=@{
  checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed';schema='V10'
  checks=@('authenticated_websocket','automatic_outbox_publication','message_created','not_delivered_before_ack','disconnect_replay_same_id','ack_checkpoint','persisted_delivery_receipt','ack_does_not_mark_read','notification_unread_sync','heartbeat_ping')
  note='Synthetic accounts in the independent Java database. Real push providers and Flutter integration are not exercised. Automatic account erasure remains enabled; no deadlines changed.'
 }
 [IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-realtime-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 foreach($socket in $sockets){try{$socket.Abort();$socket.Dispose()}catch{}}
 if($a){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$a.refresh_token}}
 if($b){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$b.refresh_token}}
}
