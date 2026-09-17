param([string]$ExpectedPublicBase='https://sssuuil-server.top')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 7 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args
 if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"}
 if($r.Content){$r.Content|ConvertFrom-Json}
}
function User{
 Api POST '/auth/register' @{phone=('194'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='群组配对测试'}
}
$null=Api GET '/health/ready'
$a=User;$b=User;$c=User
$first=Api POST ('/explore/likes/'+$b.user_id) $null $a
if($first.match -or $first.conversation_id){throw 'Unilateral like created a match'}
$match=Api POST ('/explore/likes/'+$a.user_id) $null $b
if(-not $match.match.id -or -not $match.conversation_id){throw 'Mutual match missing'}
$again=Api POST ('/explore/likes/'+$a.user_id) $null $b
if($again.match.id -ne $match.match.id){throw 'Duplicate match'}
$null=Api POST ('/explore/super-likes/'+$c.user_id) $null $a 410
$null=Api POST ('/conversations/'+$match.conversation_id+'/messages') @{content='普通喜欢配对后的聊天';client_message_id='match-smoke-message'} $a
$messages=@(Api GET ('/conversations/'+$match.conversation_id+'/messages') $null $b)
if($messages.Count -ne 1){throw 'Matched chat missing'}
$g=Api POST '/groups/' @{name='Java 群组邀请测试';tags=@('测试');join_rule_type='manual';max_members=3} $a
$share=Api GET ('/groups/'+$g.id+'/share-link') $null $a
if($share.share_url -ne ($ExpectedPublicBase.TrimEnd('/')+'/g/'+$g.link_code)){throw 'Configured domain mismatch'}
$preview=Api GET ('/group-links/'+$g.link_code)
if($preview.group_id -ne $g.id){throw 'Public preview mismatch'}
$page=Invoke-WebRequest ($base+'/g/'+$g.link_code) -TimeoutSec 10
if($page.StatusCode -ne 200 -or $page.Content -notmatch 'og:title' -or $page.Headers.'Cache-Control' -notmatch 'no-store'){throw 'Invite HTML missing'}
$null=Api GET ('/groups/'+$g.id+'/share-link') $null $b 403
$request=Api POST ('/groups/join-by-link-code?link_code='+$g.link_code) @{} $b
if($request.status -ne 'pending'){throw 'Share link bypassed manual approval'}
$requests=@(Api GET ('/groups/'+$g.id+'/join-requests') $null $a)
if($requests.Count -ne 1){throw 'Join request missing'}
$null=Api POST ('/groups/'+$g.id+'/join-requests/'+$request.id+'/approve') $null $a
$null=Api POST ('/groups/'+$g.id+'/invite') @{target_user_id=$c.user_id} $a
$members=@(Api GET ('/groups/'+$g.id+'/members') $null $b)
if($members.Count -ne 3){throw 'Member count mismatch'}
$path='/conversations/'+$g.conversation_id
$msg=Api POST ($path+'/messages') @{content='三人群聊';client_message_id='groups-smoke-message'} $a
foreach($u in @($b,$c)){
 $history=@(Api GET ($path+'/messages') $null $u)
 if($history.Count -ne 1 -or $history[0].id -ne $msg.id){throw 'Group fanout mismatch'}
 $null=Api POST ($path+'/read') $null $u
}
$null=Api PATCH ('/groups/'+$g.id+'/members/'+$b.user_id+'/role') @{role='admin'} $a
$null=Api POST ('/groups/'+$g.id+'/transfer-owner') @{target_user_id=$b.user_id} $a
$null=Api POST ('/groups/'+$g.id+'/leave') $null $a
$null=Api GET $path $null $a 404
$null=Api DELETE ('/groups/'+$g.id+'/members/'+$c.user_id) $null $b
$null=Api GET $path $null $c 404
$null=Api DELETE ('/groups/'+$g.id) $null $b
$null=Api GET ('/group-links/'+$g.link_code) $null $null 404
foreach($u in @($a,$b,$c)){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}
$report=[ordered]@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$base;public_base=$ExpectedPublicBase;result='passed'
 checks=@('ordinary_like','mutual_match','idempotency','super_like_retired','matched_chat','group_create','configured_share_domain','public_preview','invite_html','share_member_permission','link_manual_join','approve','invite','three_member_fanout','read','admin_role','transfer_owner','leave_revokes_chat','remove_revokes_chat','dissolve_revokes_link','logout')
 note='Local Java HTTP only; synthetic accounts. Public DNS/TLS/routing and platform sharing are not verified by this smoke. No legacy writes, no forced erasure.'
}
[IO.File]::WriteAllText((Join-Path $root 'docs/local-groups-explore-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5