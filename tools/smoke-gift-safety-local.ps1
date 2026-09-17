$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args;if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"};if($r.Content){$r.Content|ConvertFrom-Json}
}
$user=$null;$admin=$null
try{
 $user=Api POST '/auth/register' @{phone=('188'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='礼物安全测试'}
 $initial=Api GET '/live/gift-safety/me' $null $user
 if($initial.age_status -ne 'unverified' -or $initial.ranking_consent){throw 'Unsafe initial defaults'}
 $updated=Api PATCH '/live/gift-safety/me' @{single_limit_coins=50;daily_limit_coins=100;reminder_enabled=$false;ranking_consent=$true;acknowledge_settings_offer=$true;age_status='adult'} $user
 if($updated.age_status -ne 'unverified' -or $updated.effective_single_limit_coins -ne 50 -or $updated.today_remaining_coins -ne 100){throw 'Limits or age permissions incorrect'}
 $first=$updated.first_gift_settings_offered_at
 $again=Api PATCH '/live/gift-safety/me' @{acknowledge_settings_offer=$true} $user
 if($again.first_gift_settings_offered_at -ne $first){throw 'First acknowledgement changed'}
 $null=Api PATCH '/live/gift-safety/me' @{single_limit_coins=10001} $user 422
 $reset=Api PATCH '/live/gift-safety/me' @{single_limit_coins=$null;daily_limit_coins=$null} $user
 if($reset.effective_single_limit_coins -ne 10000 -or $reset.effective_daily_limit_coins -ne 20000){throw 'Limits reset failed'}
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $aged=Api PATCH ('/admin/gift-risk/users/'+$user.user_id+'/age-status') @{age_status='adult';reason='合成账号本地验证'} $admin
 if($aged.age_status -ne 'adult'){throw 'Admin age state failed'}
 $events=@(Api GET ('/admin/gift-risk/events?user_id='+$user.user_id+'&event_type=age_status_updated') $null $admin)
 if($events.Count -ne 1 -or $events[0].status -ne 'confirmed'){throw 'Age audit missing'}
 $null=Api POST ('/admin/gift-risk/events/'+$events[0].id+'/resolve') @{decision='reviewed';resolution='本地重复处理测试'} $admin 409
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V25';result='passed';checks=@('private_defaults','personal_limits','ranking_consent','no_self_age_escalation','first_acknowledgement','platform_limit','limit_reset','admin_age_change','risk_audit','no_reprocessing');note='Settings and audit only, synthetic user age state. Actual gift charge/rate limit/high-value confirmation integration remains separate work.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-gift-safety-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 if($user){$null=Api POST '/auth/logout' @{refresh_token=$user.refresh_token}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
