$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args;if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"};if($r.Content){$r.Content|ConvertFrom-Json}
}
function User{Api POST '/auth/register' @{phone=('191'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='支付迁移测试'}}
$a=$null;$b=$null;$admin=$null
try{
 $null=Api GET '/health/ready';$a=User;$b=User
 if(@(Api GET '/payments/packages').Count -ne 4){throw 'Coin packages mismatch'}
 $request=@{package_code='coins_30';provider='mock';client_request_id='payment-smoke-initial'}
 $order=Api POST '/payments/orders' $request $a;$again=Api POST '/payments/orders' $request $a
 if($order.id -ne $again.id -or $order.checkout_url -notlike 'datelive-mock:*'){throw 'Order idempotency mismatch'}
 $request.package_code='coins_6';$null=Api POST '/payments/orders' $request $a 409
 $null=Api GET ('/payments/orders/'+$order.id) $null $b 403
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $event=@{event_id=('smoke-event-'+[Guid]::NewGuid().ToString('N'));event_type='payment_succeeded';merchant_order_no=$order.merchant_order_no;provider_trade_no=('smoke-trade-'+[Guid]::NewGuid().ToString('N'));amount_fen=3000;currency='CNY';occurred_at=[DateTime]::UtcNow.ToString('o')}
 $body=[Text.Encoding]::UTF8.GetBytes(($event|ConvertTo-Json -Compress));$timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
 $signed=[Text.Encoding]::UTF8.GetBytes($timestamp+'.')+$body
 $mac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($local.paymentCallbackSecret))
 try{$signature=[Convert]::ToHexString($mac.ComputeHash($signed)).ToLowerInvariant()}finally{$mac.Dispose()}
 $args=@{Method='POST';Uri=($base+'/callbacks/payments/mock');ContentType='application/json';Body=$body;Headers=@{'X-Datelive-Timestamp'=$timestamp;'X-Datelive-Signature'=$signature};TimeoutSec=15;SkipHttpErrorCheck=$true}
 $paid=Invoke-WebRequest @args
 if($paid.StatusCode -ne 200 -or ($paid.Content|ConvertFrom-Json).processing_status -ne 'processed'){throw 'Signed callback failed'}
 $repeat=Invoke-WebRequest @args
 if($repeat.StatusCode -ne 200 -or -not ($repeat.Content|ConvertFrom-Json).duplicate){throw 'Duplicate callback not recognized'}
 $args.Headers.'X-Datelive-Signature'='invalid';$invalid=Invoke-WebRequest @args;if($invalid.StatusCode -ne 401){throw 'Forged callback accepted'}
 $wallet=Api GET '/wallet/me' $null $a;if($wallet.balance -ne 30){throw 'Payment credited incorrectly'}
 $detail=Api GET ('/payments/orders/'+$order.id) $null $a;if($detail.status -ne 'paid' -or -not $detail.billing_transaction_id){throw 'Paid order ledger link missing'}
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $audits=@(Api GET ('/admin/payments/callback-events?merchant_order_no='+$order.merchant_order_no) $null $admin)
 if($audits.Count -ne 1 -or $audits[0].processing_status -ne 'processed'){throw 'Callback audit mismatch'}
 $transactions=@(Api GET ('/admin/billing/transactions?reference_id='+$order.id) $null $admin)
 if($transactions.Count -ne 1 -or ($transactions[0].entries|Measure-Object -Property amount -Sum).Sum -ne 0){throw 'Payment ledger not balanced'}
 $second=Api POST '/payments/orders' @{package_code='coins_6';provider='mock';client_request_id='payment-smoke-complete'} $a
 $completion=Api POST ('/payments/orders/'+$second.id+'/mock/complete') @{} $a
 if($completion.processing_status -ne 'processed' -or (Api GET '/wallet/me' $null $a).balance -ne 36){throw 'Mock completion failed'}
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V21';result='passed';checks=@('public_packages','order_idempotency','request_conflict','order_ownership','signed_callback','duplicate_callback','invalid_signature','paid_order_ledger_link','exact_credit','admin_callback_audit','balanced_payment_ledger','mock_completion');note='Local mock orders and HMAC callbacks only. WeChat RSA/AES-GCM and SDK request/response signatures are offline-tested separately; real merchant checkout, public callback delivery and Flutter SDK integration remain unverified.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-payments-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 foreach($u in @($a,$b)){if($u){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
