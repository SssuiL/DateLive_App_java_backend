$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args;if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"};if($r.Content){$r.Content|ConvertFrom-Json}
}
function User{Api POST '/auth/register' @{phone=('189'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='直播房间测试'}}
$a=$null;$b=$null;$admin=$null;$room=$null;$live=$false
try{
 $a=User;$b=User
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $app=Api POST '/live/host-verification/applications' @{legal_name='送礼测试';id_number='TESTGIFT12345678';date_of_birth='1990-01-01';agreement_accepted=$true} $a
 $null=Api POST ('/admin/host-verification/applications/'+$app.applications[0].id+'/resolve') @{decision='approved';allow_gifts=$true;reason='本地合成账号测试'} $admin
 $room=Api POST '/live/rooms' @{title='送礼集成测试';category='聊天'} $a
 $path='/live/rooms/'+$room.id
 $check=Api POST ($path+'/preflight') @{device_id='gift-smoke-device';camera_permission=$true;microphone_permission=$true;camera_available=$true;microphone_available=$true;network_type='wifi';latency_ms=20;upload_mbps=10} $a
 $null=Api POST ($path+'/start') @{preflight_ticket=$check.ticket} $a;$live=$true
 $null=Api POST ($path+'/participants/me') $null $b
 $null=Api POST '/wallet/recharge/dev' @{amount=1000;client_request_id=('gift-credit-'+[guid]::NewGuid().ToString('N'))} $b
 $null=Api POST ($path+'/gifts') @{gift_code='rose';client_request_id='age-required-test'} $b 403
 $null=Api PATCH ('/admin/gift-risk/users/'+$b.user_id+'/age-status') @{age_status='adult';reason='合成测试账号'} $admin
 $request=@{gift_code='coffee';quantity=9;client_request_id='gift-coffee-test'}
 $gift=Api POST ($path+'/gifts') $request $b
 if($gift.total_coins -ne 90 -or $gift.host_income_coins -ne 63){throw 'Split mismatch'}
 if((Api POST ($path+'/gifts') $request $b).id -ne $gift.id){throw 'Duplicate changed record'}
 $null=Api POST ($path+'/gifts') @{gift_code='coffee';quantity=8;client_request_id='gift-coffee-test'} $b 409
 $null=Api POST ($path+'/gifts') @{gift_code='diamond';client_request_id='gift-diamond-test'} $b 409
 $confirmation=Api POST ($path+'/gift-confirmations') @{gift_code='diamond';quantity=1} $b
 $diamond=Api POST ($path+'/gifts') @{gift_code='diamond';client_request_id='gift-diamond-test';risk_confirmation_id=$confirmation.id} $b
 if($diamond.host_income_coins -ne 364){throw 'High value split mismatch'}
 if((Api GET '/live/gift-safety/me' $null $b).today_spent_coins -ne 610){throw 'Daily spend mismatch'}
 if((Api GET '/live/income/summary' $null $a).pending_coins -ne 427){throw 'Host income mismatch'}
 $rank=@(Api GET ($path+'/gift-ranking') $null $a)
 if($rank.Count -ne 1 -or $rank[0].sender_id -ne '' -or $rank[0].total_coins -ne 610){throw 'Anonymous ranking mismatch'}
 $null=Api PATCH '/live/gift-safety/me' @{ranking_consent=$true} $b
 if(@(Api GET ($path+'/gift-ranking') $null $a)[0].sender_id -ne $b.user_id){throw 'Ranking consent mismatch'}
 if(@(Api GET ($path+'/gifts') $null $a).Count -ne 2){throw 'Gift history mismatch'}
 if(@(Api GET ('/admin/live/rooms/'+$room.id+'/gifts') $null $admin).Count -ne 2){throw 'Admin gift audit mismatch'}
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V26';result='passed';checks=@('age_required','integer_income_split','duplicate_idempotency','payload_conflict','confirmation_required','confirmation_send','daily_spend','host_income','anonymous_ranking','ranking_consent','gift_history','admin_gift_audit');note='Local synthetic coin recharge and mock room only. No real payment, streaming, settlement or gift WebSocket broadcast.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-live-gift-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 if($live){$null=Api POST ('/live/rooms/'+$room.id+'/end') $null $a}
 if($a -and $admin){$null=Api POST ('/admin/host-verification/qualifications/'+$a.user_id+'/suspend') @{reason='合成测试结束'} $admin}
 foreach($u in @($a,$b)){if($u){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}