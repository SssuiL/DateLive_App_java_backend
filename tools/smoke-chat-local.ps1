$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-ChatApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 $response=Invoke-RestMethod @requestArgs
 foreach($item in $response){$item}
}
function New-ChatUser{
 $phone='196'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
 Invoke-ChatApi POST '/auth/register' @{phone=$phone;password=('Chat-'+[Guid]::NewGuid().ToString('N'));nickname='聊天迁移测试'}
}
$null=Invoke-ChatApi GET '/health/ready'
$a=New-ChatUser
$b=New-ChatUser
$request=Invoke-ChatApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
$null=Invoke-ChatApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
$conversations=@(Invoke-ChatApi GET '/conversations/' $null $a.access_token)
if($conversations.Count -ne 1){throw 'Conversation not created'}
$path='/conversations/'+$conversations[0].id
$page=Invoke-ChatApi GET '/conversations/page?limit=1' $null $a.access_token
if($page.items.Count -ne 1 -or $page.has_more){throw 'Conversation page mismatch'}
$settings=Invoke-ChatApi PATCH ($path+'/settings') @{pinned=$true;muted=$true} $b.access_token
if(-not $settings.muted -or -not $settings.pinned){throw 'Member settings failed'}
$message=Invoke-ChatApi POST ($path+'/messages') @{client_message_id='smoke-message-first';content='Java 本地聊天测试'} $a.access_token
$retry=Invoke-ChatApi POST ($path+'/messages') @{client_message_id='smoke-message-first';content='Java 本地聊天测试'} $a.access_token
if($retry.id -ne $message.id){throw 'Message retry not idempotent'}
$unread=Invoke-ChatApi GET $path $null $b.access_token
if($unread.unread_count -ne 1){throw 'Unread counter mismatch'}
$reply=Invoke-ChatApi POST ($path+'/messages') @{client_message_id='smoke-message-reply';content='引用回复测试';reply_to_message_id=$message.id} $b.access_token
if($reply.reply_preview.id -ne $message.id){throw 'Reply preview mismatch'}
$history=Invoke-ChatApi GET ($path+'/messages/page?limit=1') $null $a.access_token
if($history.items[0].id -ne $reply.id -or -not $history.has_more){throw 'History page mismatch'}
$older=Invoke-ChatApi GET ($path+'/messages/page?limit=1&cursor='+$history.next_cursor) $null $a.access_token
if($older.items[0].id -ne $message.id -or $older.has_more){throw 'History cursor mismatch'}
$read=Invoke-ChatApi POST ($path+'/read') $null $b.access_token
if($read.unread_count -ne 0){throw 'Read counter failed'}
$all=@(Invoke-ChatApi GET ($path+'/messages') $null $a.access_token)
if($all.Count -ne 2 -or $all[0].read_by.Count -ne 2 -or $all[0].delivered_to_user_ids -notcontains $b.user_id){throw 'Receipt mismatch'}
$null=Invoke-ChatApi POST ($path+'/read') $null $a.access_token
$events=@(Invoke-ChatApi GET '/notifications/events' $null $b.access_token)
$chatEvent=@($events|Where-Object category -eq 'chat')
if($chatEvent.Count -ne 1 -or $chatEvent[0].suppress_reason -ne 'conversation_muted'){throw 'Chat notification mismatch'}
$own=Invoke-ChatApi GET $path $null $a.access_token
if($own.pinned -or $own.muted){throw 'Other member settings leaked'}
$null=Invoke-ChatApi POST '/auth/logout' @{refresh_token=$a.refresh_token}
$null=Invoke-ChatApi POST '/auth/logout' @{refresh_token=$b.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('friend_conversation','conversation_page','per_user_settings','send_text','idempotent_retry','unread_counter','reply_preview','history_cursor','mark_read','read_receipts','muted_notification','logout')
 note='Two synthetic Java accounts only. WebSocket and real push are not implemented. No account deadline changed and no erasure forced.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-chat-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
