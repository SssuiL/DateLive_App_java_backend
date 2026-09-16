$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-SocialApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 $response=Invoke-RestMethod @requestArgs
 foreach($item in $response){$item}
}
function New-SocialUser {
 $phone='195'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
 Invoke-SocialApi POST '/auth/register' @{phone=$phone;password=('Social-'+[Guid]::NewGuid().ToString('N'));nickname='社交迁移测试'}
}
$null=Invoke-SocialApi GET '/health/ready'
$a=New-SocialUser
$b=New-SocialUser
$directory=@(Invoke-SocialApi GET ("/users/?q="+$b.user_id) $null $a.access_token)
if($directory.Count -ne 1 -or $directory[0].id -ne $b.user_id){throw 'Directory mismatch'}
$request=Invoke-SocialApi POST '/friends/requests' @{target_user_id=$b.user_id;message='本地合成账号联调'} $a.access_token
$again=Invoke-SocialApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
if($request.id -ne $again.id){throw 'Duplicate request was not idempotent'}
$events=@(Invoke-SocialApi GET '/notifications/events' $null $b.access_token)
if($events.Count -ne 1 -or $events[0].source_id -ne $request.id){throw 'Notification mismatch'}
$unread=Invoke-SocialApi GET '/notifications/unread-count' $null $b.access_token
if($unread.unread_count -ne 1){throw 'Unread count mismatch'}
$accepted=Invoke-SocialApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
if($accepted.status -ne 'accepted'){throw 'Acceptance failed'}
$friends=@(Invoke-SocialApi GET '/friends/' $null $a.access_token)
if($friends.Count -ne 1 -or $friends[0].user_id -ne $b.user_id){throw 'Friend profile mismatch'}
$block=Invoke-SocialApi POST '/safety/blocks' @{target_user_id=$b.user_id} $a.access_token
$hidden=@(Invoke-SocialApi GET '/friends/' $null $b.access_token)
if($hidden.Count -ne 0){throw 'Blocked friendship remained visible'}
$null=Invoke-SocialApi DELETE ("/safety/blocks/"+$block.id) $null $a.access_token
$visible=@(Invoke-SocialApi GET '/friends/' $null $b.access_token)
if($visible.Count -ne 1){throw 'Unblock failed'}
$null=Invoke-SocialApi DELETE ("/friends/"+$b.user_id) $null $a.access_token
$empty=@(Invoke-SocialApi GET '/friends/' $null $a.access_token)
if($empty.Count -ne 0){throw 'Delete friend failed'}
$null=Invoke-SocialApi POST ("/notifications/events/"+$events[0].id+"/read") $null $b.access_token
$after=Invoke-SocialApi GET '/notifications/unread-count' $null $b.access_token
if($after.unread_count -ne 0){throw 'Read count mismatch'}
$null=Invoke-SocialApi POST '/auth/logout' @{refresh_token=$a.refresh_token}
$null=Invoke-SocialApi POST '/auth/logout' @{refresh_token=$b.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('public_directory','request','idempotent_request','notification_event','unread_count','accept','friend_profile','bidirectional_block','unblock','delete_friend','read_notification','logout')
 note='Two synthetic accounts only. No account deadline modified, no erasure forced. Conversation retained after deleting friendship, matching legacy behavior. Push delivery is not implemented.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-social-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
