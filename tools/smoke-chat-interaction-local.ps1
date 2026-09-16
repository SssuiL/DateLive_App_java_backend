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
$path='/conversations/'+$conversations[0].id
$first=Invoke-ChatApi POST ($path+'/messages') @{client_message_id='interaction-smoke-first';content='HiddenNeedle'} $a.access_token
$reply=Invoke-ChatApi POST ($path+'/messages') @{content='引用测试';reply_to_message_id=$first.id} $b.access_token
$search=Invoke-ChatApi GET ($path+'/messages/search?q=hiddenneedle') $null $b.access_token
if($search.items.Count -ne 1){throw 'Search failed'}
$null=Invoke-ChatApi DELETE ($path+'/messages/'+$first.id) $null $b.access_token
$hidden=@(Invoke-ChatApi GET ($path+'/messages') $null $b.access_token)
if($hidden.Count -ne 1 -or $null -ne $hidden[0].reply_preview){throw 'Personal hide or reply visibility failed'}
$peer=@(Invoke-ChatApi GET ($path+'/messages') $null $a.access_token)
if($peer.Count -ne 2){throw 'Personal hide changed peer history'}
$recalled=Invoke-ChatApi POST ($path+'/messages/'+$first.id+'/recall') $null $a.access_token
if(-not $recalled.is_recalled -or $recalled.content -ne '消息已撤回'){throw 'Recall failed'}
$null=Invoke-ChatApi POST ($path+'/messages/'+$first.id+'/recall') $null $a.access_token
$masked=@(Invoke-ChatApi GET ($path+'/messages') $null $a.access_token)
if($masked[1].reply_preview.content -ne '原消息已撤回'){throw 'Recalled reply preview leaked'}
$search=Invoke-ChatApi GET ($path+'/messages/search?q=hiddenneedle') $null $a.access_token
if($search.items.Count -ne 0){throw 'Recalled message searchable'}
$cleared=Invoke-ChatApi DELETE ($path+'/messages') $null $b.access_token
if($null -ne $cleared.last_message -or $cleared.unread_count -ne 0){throw 'Clear state failed'}
$empty=@(Invoke-ChatApi GET ($path+'/messages') $null $b.access_token)
$peer=@(Invoke-ChatApi GET ($path+'/messages') $null $a.access_token)
if($empty.Count -ne 0 -or $peer.Count -ne 2){throw 'Clear was not personal'}
$null=Invoke-ChatApi POST ($path+'/messages') @{content='清空后的新消息'} $a.access_token
$new=@(Invoke-ChatApi GET ($path+'/messages') $null $b.access_token)
if($new.Count -ne 1 -or $new[0].content -ne '清空后的新消息'){throw 'New message hidden by old clear'}
$null=Invoke-ChatApi POST '/auth/logout' @{refresh_token=$a.refresh_token}
$null=Invoke-ChatApi POST '/auth/logout' @{refresh_token=$b.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('literal_search','personal_hide','hidden_reply_preview','peer_history_retained','recall','idempotent_recall','recalled_reply_redaction','search_excludes_recalled','personal_clear','clear_preserves_peer','new_message_after_clear','logout')
 note='Two synthetic Java accounts. Real two-minute recall window used; no account deadlines changed, no erasure forced, no WebSocket or push delivery claimed.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-chat-interaction-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
