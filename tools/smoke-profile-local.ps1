$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-ProfileApi($method,$path,$body=$null,$token=$null) {
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 Invoke-RestMethod @requestArgs
}
$null=Invoke-ProfileApi GET '/health/ready'
$phone='197'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
$registered=Invoke-ProfileApi POST '/auth/register' @{phone=$phone;password=('Profile-'+[Guid]::NewGuid().ToString('N'));nickname='资料冒烟'}
$token=$registered.access_token
$profile=Invoke-ProfileApi GET '/profiles/me' $null $token
if($profile.user_id -ne $registered.user_id){throw 'Unexpected profile owner'}
$profile=Invoke-ProfileApi PATCH '/profiles/me' @{nickname='资料已更新';bio='喜欢阅读和散步';tags=@('阅读','阅读','散步');age=25;distance_visible=$false} $token
if($profile.nickname -ne '资料已更新' -or @($profile.tags).Count -ne 2){throw 'Profile update mismatch'}
$me=Invoke-ProfileApi GET '/auth/me' $null $token
if($me.nickname -ne $profile.nickname){throw 'Nickname synchronization failed'}
$preferences=Invoke-ProfileApi GET '/notifications/preferences' $null $token
if($preferences.night_quiet_start -ne '22:00'){throw 'Preference defaults mismatch'}
$preferences=Invoke-ProfileApi PATCH '/notifications/preferences' @{chat_messages_enabled=$false;night_quiet_enabled=$true;night_quiet_start='23:30'} $token
if($preferences.chat_messages_enabled -or $preferences.night_quiet_start -ne '23:30'){throw 'Preference patch failed'}
$events=Invoke-ProfileApi GET '/notifications/events' $null $token
$unread=Invoke-ProfileApi GET '/notifications/unread-count' $null $token
$read=Invoke-ProfileApi POST '/notifications/events/read-all' $null $token
if(@($events).Count -ne 0 -or $unread.unread_count -ne 0 -or $read.marked_count -ne 0){throw 'New account notification state mismatch'}
$null=Invoke-ProfileApi POST '/auth/logout' @{refresh_token=$registered.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('profile_defaults','profile_patch','nickname_sync','tag_deduplication','notification_defaults','notification_patch','empty_events','unread_count','read_all_empty','logout')
 note='One synthetic local account. Media permissions and non-empty notifications tested in isolated integration tests, not this smoke.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-profile-notification-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
