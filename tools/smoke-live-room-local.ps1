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
 if((Api GET '/live/categories').primary.Count -ne 4){throw 'Category mismatch'}
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $application=Api POST '/live/host-verification/applications' @{legal_name='直播测试';id_number='TESTLIVE12345678';date_of_birth='1990-01-01';agreement_accepted=$true} $a
 $null=Api POST ('/admin/host-verification/applications/'+$application.applications[0].id+'/resolve') @{decision='approved';allow_gifts=$false;reason='本地合成账号验证'} $admin
 $room=Api POST '/live/rooms' @{title='本地模拟直播';category='聊天';tags=@('测试')} $a
 $path='/live/rooms/'+$room.id
 $null=Api GET $path $null $b 404
 $null=Api POST ($path+'/start') @{} $a 403
 $check=Api POST ($path+'/preflight') @{device_id='local-smoke-device';camera_permission=$true;microphone_permission=$true;camera_available=$true;microphone_available=$true;network_type='wifi';latency_ms=20;upload_mbps=10} $a
 if(-not $check.can_start -or -not $check.ticket){throw 'Preflight ticket not issued'}
 $started=Api POST ($path+'/start') @{preflight_ticket=$check.ticket} $a;$live=$true
 if($started.status -ne 'live' -or $started.online_count -ne 1){throw 'Start mismatch'}
 $session=Api GET ($path+'/stream-session') $null $a
 if(-not $session.is_mock -or $session.push_url){throw 'Mock session claims actual streaming'}
 $null=Api POST ($path+'/participants/me') $null $b
 if((Api GET $path $null $b).online_count -ne 2){throw 'Presence mismatch'}
 $null=Api DELETE ($path+'/participants/me') $null $a 400
 $null=Api DELETE ($path+'/participants/me') $null $b
 if((Api GET $path $null $a).online_count -ne 1){throw 'Leave mismatch'}
 $audit=@(Api GET ('/admin/live-preflight?room_id='+$room.id) $null $admin)
 if($audit.Count -ne 1 -or $audit[0].result -ne 'consumed' -or $audit[0].ticket){throw 'Preflight audit mismatch'}
 $ended=Api POST ($path+'/end') $null $a;$live=$false
 if($ended.status -ne 'ended' -or $ended.online_count -ne 0){throw 'End mismatch'}
 $null=Api GET $path $null $b 404
 $probe=Api POST '/live/network-probe' @{payload=('测'*1024)} $b
 if($probe.received_bytes -ne 3072){throw 'UTF8 probe mismatch'}
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V24';result='passed';checks=@('categories','host_qualification','draft_ownership','requires_preflight','ticket_issuance','start','mock_session','join','host_cannot_leave','viewer_leave','consumed_ticket_audit','end','ended_room_hidden','utf8_network_probe');note='Local mock room lifecycle only; no real camera/network attestation or audio/video streaming. Broadcast, governance, gifting, schedules and provider integration remain separate work.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-live-room-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 if($live){$null=Api POST ('/live/rooms/'+$room.id+'/end') $null $a}
 if($a -and $admin){$null=Api POST ('/admin/host-verification/qualifications/'+$a.user_id+'/suspend') @{reason='本地合成账号验证结束'} $admin}
 foreach($u in @($a,$b)){if($u){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
