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
 $user=Api POST '/auth/register' @{phone=('190'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='主播流程测试'}
 $initial=Api GET '/live/host-verification/me' $null $user
 if($initial.qualification.identity_status -ne 'unverified'){throw 'Initial qualification mismatch'}
 $payload=@{legal_name='合成测试';id_number='TESTONLY87654321';date_of_birth='1990-01-01';agreement_accepted=$true}
 $pending=Api POST '/live/host-verification/applications' $payload $user
 if($pending.qualification.identity_status -ne 'pending' -or $pending.applications[0].legal_name_masked -ne '合***'){throw 'Application masking mismatch'}
 if(($pending|ConvertTo-Json -Depth 8) -match 'TESTONLY87654321|id_number_hash'){throw 'Identity material exposed'}
 $null=Api POST '/live/host-verification/applications' $payload $user 409
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $listed=@(Api GET ('/admin/host-verification/applications?user_id='+$user.user_id+'&status=pending_review') $null $admin)
 if($listed.Count -ne 1){throw 'Admin filter failed'}
 $id=$pending.applications[0].id
 $approved=Api POST ('/admin/host-verification/applications/'+$id+'/resolve') @{decision='approved';allow_gifts=$true;reason='合成账号本地验证'} $admin
 if($approved.qualification.host_permission_status -ne 'enabled' -or -not $approved.qualification.can_receive_gifts){throw 'Approval mismatch'}
 $null=Api POST ('/admin/host-verification/applications/'+$id+'/resolve') @{decision='approved'} $admin 409
 $suspended=Api POST ('/admin/host-verification/qualifications/'+$user.user_id+'/suspend') @{reason='合成账号暂停验证'} $admin
 if($suspended.qualification.host_permission_status -ne 'suspended' -or $suspended.qualification.can_receive_gifts){throw 'Suspension mismatch'}
 $null=Api POST '/live/host-verification/applications' $payload $user 409
 $restored=Api POST ('/admin/host-verification/qualifications/'+$user.user_id+'/restore') $null $admin
 if($restored.qualification.host_permission_status -ne 'enabled' -or $restored.qualification.can_receive_gifts){throw 'Restoration granted unintended gifts'}
 $events=Api GET '/notifications/events' $null $user
 if(($events|ConvertTo-Json -Depth 12) -notmatch 'host_verification_reviewed'){throw 'Review notification missing'}
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V23';result='passed';checks=@('initial_state','submission','masking','duplicate_pending','admin_filter','approval','duplicate_review','suspension','no_reapplication_bypass','restore_without_gifts','official_notification');note='Synthetic identity and manual local review only; no external identity verification or live streaming exercised.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-host-verification-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 if($user -and $admin){$null=Api POST ('/admin/host-verification/qualifications/'+$user.user_id+'/suspend') @{reason='本地合成账号验证结束'} $admin}
 if($user){$null=Api POST '/auth/logout' @{refresh_token=$user.refresh_token}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
