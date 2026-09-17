$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=20;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args
 if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"}
 if($r.Content){$r.Content|ConvertFrom-Json}
}
function User{Api POST '/auth/register' @{phone=('193'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='动态迁移测试'}}
function Upload($user,$source,$bytes){
 $client=[Net.Http.HttpClient]::new();$form=[Net.Http.MultipartFormDataContent]::new()
 $content=[Net.Http.ByteArrayContent]::new([byte[]]$bytes);$content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
 $form.Add([Net.Http.StringContent]::new('image'),'media_type');$form.Add([Net.Http.StringContent]::new($source),'source');$form.Add($content,'file','fixture.png')
 $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$user.access_token)
 try{$r=$client.PostAsync(($base+'/media/upload'),$form).GetAwaiter().GetResult();try{if(-not $r.IsSuccessStatusCode){throw 'Upload failed'};($r.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json)}finally{$r.Dispose()}}finally{$form.Dispose();$client.Dispose()}
}
$a=$null;$b=$null;$c=$null;$admin=$null
try{
 $null=Api GET '/health/ready'
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $a=User;$b=User;$c=User
 $request=Api POST '/friends/requests' @{target_user_id=$b.user_id} $a
 $null=Api POST ('/friends/requests/'+$request.id+'/accept') $null $b
 $p=Api POST '/posts/' @{text='仅好友可见的动态';visibility='friends'} $a 201
 $path='/posts/'+$p.id
 $null=Api GET $path $null $b
 $null=Api GET $path $null $c 404
 $liked=Api POST ($path+'/likes') $null $b
 if($liked.like_count -ne 1 -or -not $liked.liked_by_me){throw 'Like state mismatch'}
 $again=Api POST ($path+'/likes') $null $b
 if($again.like_count -ne 1){throw 'Duplicate like'}
 $comment=Api POST ($path+'/comments') @{content='评论'} $b 201
 $reply=Api POST ($path+'/comments') @{content='回复';parent_comment_id=$comment.id} $a 201
 $page=Api GET ($path+'/comments/page?reply_preview_limit=1') $null $b
 if($page.items[0].reply_count -ne 1 -or $page.items[0].replies[0].id -ne $reply.id){throw 'Threaded reply mismatch'}
 $null=Api DELETE ($path+'/comments/'+$comment.id) $null $b 204
 $flat=@(Api GET ($path+'/comments') $null $a)
 if($flat.Count -ne 2 -or -not $flat[0].is_deleted -or $flat[1].id -ne $reply.id){throw 'Deleted root lost replies'}
 $mediaTools=Get-Content -Raw (Join-Path $root '.tools/ffmpeg-install.json')|ConvertFrom-Json
 $png=Join-Path $root '.tools/posts-smoke.png'
 & $mediaTools.ffmpeg -v error -nostdin -y -f lavfi -i 'color=c=blue:s=64x48:r=1' -frames:v 1 -threads 1 $png
 if($LASTEXITCODE -ne 0){throw 'Fixture generation failed'}
 $asset=Upload $a 'post' ([IO.File]::ReadAllBytes($png))
 $image=Api POST '/posts/' @{type='image';media_asset_ids=@($asset.id);visibility='public';allow_media_save=$false} $a 201
 $imagePath='/posts/'+$image.id
 $null=Api GET $imagePath $null $b 404
 $null=Api POST ('/admin/moderation/media/'+$asset.id+'/approve') @{reason='Synthetic post smoke'} $admin
 $visible=Api GET $imagePath $null $b
 if($visible.moderation_status -ne 'approved' -or -not $visible.media[0].url){throw 'Approved media not visible'}
 $url=$visible.media[0].url
 $null=Invoke-WebRequest ($base+$url) -TimeoutSec 15
 $reaction=Api PUT ($imagePath+'/media/'+$asset.id+'/reaction') @{emoji='👍'} $b
 if($reaction.reaction_total -ne 1){throw 'Media reaction missing'}
 $null=Api GET ($imagePath+'/media/'+$asset.id+'/download') $null $b 403
 $null=Api GET ($imagePath+'/media/'+$asset.id+'/download') $null $a
 $pending=Upload $b 'post_comment' ([IO.File]::ReadAllBytes($png))
 $pendingComment=Api POST ($imagePath+'/comments') @{media_asset_id=$pending.id} $b 201
 $null=Api DELETE ($imagePath+'/comments/'+$pendingComment.id) $null $a 204
 $null=Api DELETE $imagePath $null $a 204
 $revoked=Invoke-WebRequest ($base+$url) -SkipHttpErrorCheck -TimeoutSec 15
 if($revoked.StatusCode -ne 404){throw 'Deleted post media still accessible'}
 $null=Api DELETE $path $null $a 204
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V19';result='passed';checks=@('friends_visibility','stranger_denied','like_idempotency','comment_thread','comment_page_preview','deleted_root_preserves_reply','pending_post_hidden','admin_approval_publishes','signed_media_download','emoji_reaction','save_preference','owner_download','owner_removes_pending_comment','delete_revokes_old_media_url');note='Synthetic local HTTP and real image processing. Cloud, Flutter and real external moderation are not verified by this smoke.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-posts-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 foreach($u in @($a,$b,$c)){if($u){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
