$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$token=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($token){$args.Headers=@{Authorization=('Bearer '+$token)}}
 $r=Invoke-WebRequest @args;if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"};if($r.Content){$r.Content|ConvertFrom-Json}
}
$token=$null;$gift=$null;$client=$null
try{
 $catalog=@(Api GET '/live/gifts');if(-not ($catalog|Where-Object code -eq 'rose')){throw 'Default catalog missing'}
 $null=Api GET '/admin/live-gifts' $null $null 401
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $token=(Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}).access_token
 $code='smoke_'+[Guid]::NewGuid().ToString('N').Substring(0,20)
 $gift=Api POST '/admin/live-gifts' @{code=$code;name='礼物迁移验证';price_coins=25} $token
 $null=Api POST '/admin/live-gifts' @{code=$code;name='礼物迁移验证';price_coins=25} $token 409
 $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
 $bytes=[Text.Encoding]::UTF8.GetBytes('{"v":"5.7.4","fr":30,"ip":0,"op":30,"layers":[]}')
 $form=[Net.Http.MultipartFormDataContent]::new();$file=[Net.Http.ByteArrayContent]::new($bytes);$file.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/json');$form.Add($file,'file','fixture.json')
 try{$response=$client.PostAsync(($base+'/admin/live-gifts/'+$gift.id+'/assets/animation'),$form).GetAwaiter().GetResult();if([int]$response.StatusCode -ne 200){throw 'Animation upload failed'};$uploaded=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json}finally{$form.Dispose()}
 $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
 if($uploaded.animation_checksum -ne $hash -or $uploaded.resource_version -ne 2 -or $uploaded.renderer_type -ne 'lottie'){throw 'Animation metadata mismatch'}
 $asset=Invoke-WebRequest -Uri ($base+([Uri]$uploaded.animation_url).AbsolutePath) -TimeoutSec 15
 if(($asset.Content|ConvertFrom-Json).v -ne '5.7.4' -or $asset.Headers.'X-Content-Type-Options' -ne 'nosniff'){throw 'Public resource mismatch'}
 $null=Api PATCH ('/admin/live-gifts/'+$gift.id) @{status='inactive'} $token
 if(@(Api GET '/live/gifts')|Where-Object code -eq $code){throw 'Inactive gift visible'}
 $null=Api PATCH ('/admin/live-gifts/'+$gift.id) @{status='active';price_coins=30} $token
 if((@(Api GET '/live/gifts')|Where-Object code -eq $code).price_coins -ne 30){throw 'Updated gift not visible'}
 $null=Api PATCH ('/admin/live-gifts/'+$gift.id) @{price_coins=0} $token 422
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V22';result='passed';checks=@('default_catalog','admin_authentication','create','duplicate_code','animation_upload','resource_version','checksum','public_resource','deactivate','reactivate','price_update','validation');note='Local catalog and synthetic Lottie resource only. Flutter animation rendering, cloud object storage and gift charging/settlement are separate migration work.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-gift-catalog-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 if($gift -and $token){$null=Api PATCH ('/admin/live-gifts/'+$gift.id) @{status='inactive'} $token}
 if($token){$null=Api POST '/admin/auth/logout' $null $token}
 if($client){$client.Dispose()}
}
