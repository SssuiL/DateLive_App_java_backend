$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-JavaApi($method,$path,$body=$null,$token=$null) {
 $args=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$args.Headers=@{Authorization="Bearer $token"}}
 Invoke-RestMethod @args
}
function Confirm-Unauthorized($token) {
 try { $null=Invoke-JavaApi GET '/auth/me' $null $token; throw 'Revoked token remained valid' }
 catch { if([int]$_.Exception.Response.StatusCode -ne 401){throw} }
}
$null=Invoke-JavaApi GET '/health/ready'
$phone='198'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
$password='Java-test-'+[Guid]::NewGuid().ToString('N')
$watch=[Diagnostics.Stopwatch]::StartNew()
$otp=Invoke-JavaApi POST '/auth/sms/send' @{phone=$phone;purpose='register'}
if(-not $otp.dev_code){throw 'Enable local development SMS with start-local.ps1 -DevelopmentSms'}
$user=Invoke-JavaApi POST '/auth/register/verify' @{phone=$phone;password=$password;nickname='Java验证码冒烟';request_id=$otp.request_id;code=$otp.dev_code}
$watch.Stop()
$registrationMs=[Math]::Round($watch.Elapsed.TotalMilliseconds,2)
$me=Invoke-JavaApi GET '/auth/me' $null $user.access_token
if($me.phone -ne $phone){throw 'Unexpected registered account'}
$otp=Invoke-JavaApi POST '/auth/sms/send' @{phone=$phone;purpose='login'}
$login=Invoke-JavaApi POST '/auth/sms/login' @{phone=$phone;request_id=$otp.request_id;code=$otp.dev_code}
$deviceId='smoke-device-'+[Guid]::NewGuid().ToString('N')
$device=Invoke-JavaApi POST '/notifications/devices' @{device_id=$deviceId;push_token=('test-token-'+[Guid]::NewGuid().ToString('N'));platform='android'} $login.access_token
if($device.PSObject.Properties.Name -contains 'push_token'){throw 'Push response leaked token'}
$devices=Invoke-JavaApi GET '/notifications/devices' $null $login.access_token
if(@($devices).Count -ne 1){throw 'Unexpected device list'}
$disabled=Invoke-JavaApi DELETE ("/notifications/devices/$deviceId") $null $login.access_token
if($disabled.status -ne 'disabled'){throw 'Device disable failed'}
$revoked=Invoke-JavaApi POST '/auth/sessions/revoke-others' $null $login.access_token
if($revoked.revoked_count -ne 1){throw 'Unexpected revoked session count'}
Confirm-Unauthorized $user.access_token
$otp=Invoke-JavaApi POST '/auth/sms/send' @{phone=$phone;purpose='password_reset'}
$newPassword='Reset-test-'+[Guid]::NewGuid().ToString('N')
$reset=Invoke-JavaApi POST '/auth/password/reset' @{phone=$phone;request_id=$otp.request_id;code=$otp.dev_code;new_password=$newPassword}
if($reset.status -ne 'ok'){throw 'Reset failed'}
Confirm-Unauthorized $login.access_token
$newSession=Invoke-JavaApi POST '/auth/login' @{phone=$phone;password=$newPassword}
$null=Invoke-JavaApi POST '/auth/logout' @{refresh_token=$newSession.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o')
 target=$javaBase; sms_provider='development'; real_sms_sent=$false
 result='passed'; verified_registration_with_local_dispatch_ms=$registrationMs
 checks=@('sms_register','sms_login','push_register_list_disable','revoke_other_sessions','password_reset','old_access_rejected','new_password_login','logout')
 note='One local synthetic account; no real provider, mobile or load test.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-sms-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
