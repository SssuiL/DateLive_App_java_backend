$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8200'
function Api($method,$path,$body=$null,$user=$null,$expected=200){
 $args=@{Method=$method;Uri=($base+$path);TimeoutSec=15;SkipHttpErrorCheck=$true}
 if($null -ne $body){$args.ContentType='application/json; charset=utf-8';$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 if($user){$args.Headers=@{Authorization=('Bearer '+$user.access_token)}}
 $r=Invoke-WebRequest @args;if([int]$r.StatusCode -ne $expected){throw "Unexpected HTTP $($r.StatusCode): $method $path"};if($r.Content){$r.Content|ConvertFrom-Json}
}
function User{Api POST '/auth/register' @{phone=('192'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999));password=('Fixture-'+[Guid]::NewGuid().ToString('N'));nickname='钱包迁移测试'}}
$a=$null;$b=$null;$admin=$null
try{
 $null=Api GET '/health/ready';$a=User;$b=User
 $wallet=Api GET '/wallet/me' $null $a;if($wallet.balance -ne 0){throw 'Initial balance is not zero'}
 $null=Api GET '/wallet/me' $null $null 401
 $request=@{amount=25;client_request_id='wallet-smoke-initial';remark='本地模拟，无真实资金'}
 $first=Api POST '/wallet/recharge/dev' $request $a;$again=Api POST '/wallet/recharge/dev' $request $a
 if($first.balance -ne 25 -or $again.balance -ne 25){throw 'Duplicate recharge'}
 $request.amount=26;$null=Api POST '/wallet/recharge/dev' $request $a 409
 $ledger=@(Api GET '/wallet/ledger' $null $a)
 if($ledger.Count -ne 1 -or $ledger[0].entries.Count -ne 1 -or $ledger[0].entries[0].owner_id -ne $a.user_id){throw 'User ledger privacy mismatch'}
 if(@(Api GET '/wallet/ledger' $null $b).Count -ne 0){throw 'Peer ledger disclosed'}
 $bills=@(Api GET '/wallet/bills?direction=income' $null $a)
 if($bills.Count -ne 1 -or $bills[0].amount -ne 25){throw 'Income bills mismatch'}
 if(@(Api GET '/wallet/bills?direction=expense' $null $a).Count -ne 0){throw 'Unexpected expense'}
 $summary=Api GET '/wallet/summary?days=30' $null $a
 if($summary.balance -ne 25 -or $summary.recharge_coins -ne 25 -or $summary.bill_count -ne 1){throw 'Wallet summary mismatch'}
 if(@(Api GET '/wallet/transactions' $null $a).Count -ne 1){throw 'Legacy wallet history mismatch'}
 $null=Api GET '/admin/billing/accounts' $null $a 401
 $local=Get-Content -Raw (Join-Path $root '.tools/local-development.json')|ConvertFrom-Json
 $admin=Api POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $all=@(Api GET ('/admin/billing/transactions?user_id='+$a.user_id) $null $admin)
 if($all.Count -ne 1 -or $all[0].entries.Count -ne 2 -or ($all[0].entries|Measure-Object -Property amount -Sum).Sum -ne 0){throw 'Admin ledger is not balanced'}
 $accounts=@(Api GET ('/admin/billing/accounts?owner_id='+$a.user_id) $null $admin)
 if($accounts.Count -ne 1 -or $accounts[0].balance -ne 25){throw 'Account balance mismatch'}
 $report=[ordered]@{checked_at=[DateTime]::UtcNow.ToString('o');target=$base;schema='V20';result='passed';checks=@('initial_zero','authentication','idempotent_recharge','payload_conflict','user_ledger_privacy','peer_isolation','income_expense_filters','summary','legacy_history','admin_authorization','balanced_admin_entries','account_reconciliation');note='Local synthetic coins only, explicitly enabled with -DevelopmentBilling. No real provider, callback or financial transaction was exercised. Concurrent debit and rollback are separately integration-tested.'}
 [IO.File]::WriteAllText((Join-Path $root 'docs/local-wallet-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false));$report|ConvertTo-Json -Depth 5
}finally{
 foreach($u in @($a,$b)){if($u){$null=Api POST '/auth/logout' @{refresh_token=$u.refresh_token}}}
 if($admin){$null=Api POST '/admin/auth/logout' $null $admin}
}
