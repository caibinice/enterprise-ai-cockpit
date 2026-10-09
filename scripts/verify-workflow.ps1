param(
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [ValidateSet('offline','live')][string]$Mode = 'offline',
  [string]$OutputPath = '',
  [switch]$FocusOnly
)
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
if (-not $env:ACTION_PASSWORD) { throw '请从本地凭据向进程环境注入 ACTION_PASSWORD。' }
$auth = Invoke-RestMethod "$base/api/action-auth/verify" -Method Post -ContentType 'application/json' -Body (@{password=$env:ACTION_PASSWORD}|ConvertTo-Json)
$headers = @{Authorization="Bearer $($auth.token)"}
$cases = @(
  @{name='rag';message='Aero耳机退货政策，7天未使用';fault='none';node='retrieve';count=1},
  @{name='vision';message='图片中的耳机故障如何处理';fault='none';node='vision';count=1},
  @{name='report';message='查询2026-09销售报表';fault='none';node='report_mcp';count=1},
  @{name='tool';message='查询SO20261001并试算退款金额';fault='none';node='tool_execute';count=2},
  @{name='multi';message='请分别说明Aero耳机退货政策、SO20261001退款金额和2026-09销售报表';fault='none';node='specialist';count=3},
  @{name='human';message='请人工客服处理赔偿争议';fault='none';node='human_reply';count=1},
  @{name='requery';message='Aero耳机退货政策';fault='retrieval_gap';node='retrieve';count=2},
  @{name='timeout';message='查询SO20261001并试算退款金额';fault='tool_timeout';node='tool_execute';count=3}
)
if ($FocusOnly) { $cases=@($cases|Where-Object {$_.name -in @('tool','multi')}) }
$results=@()
foreach($case in $cases) {
  $body=@{message=$case.message;mode=$Mode;knowledgeBase='support';maxIterations=3;fault=$case.fault}
  if($case.name -eq 'vision') {
    $image=Invoke-WebRequest "$base/api/workflow/demo-image"
    $body.image='data:image/png;base64,'+[Convert]::ToBase64String($image.Content)
    $body.imageName='customer-e02.png'
  }
  $created=Invoke-RestMethod "$base/api/workflow/runs" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json)))
  $deadline=[DateTime]::UtcNow.AddMinutes(5)
  $resumed=$false
  do {
    Start-Sleep -Milliseconds 250
    $run=Invoke-RestMethod "$base/api/workflow/runs/$($created.id)"
    if($case.name -eq 'human' -and $run.status -eq 'WAITING_HUMAN' -and -not $resumed) {
      $reply=@{reply='已核实客户问题，将在一个工作日内反馈处理进展。'}|ConvertTo-Json
      $null=Invoke-RestMethod "$base/api/workflow/runs/$($run.id)/human" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($reply))
      $resumed=$true;$run.status='RUNNING'
    }
  } while($run.status -eq 'RUNNING' -and [DateTime]::UtcNow -lt $deadline)
  if($run.status -ne 'COMPLETED') { throw "$($case.name) status=$($run.status)" }
  $count=@($run.events|Where-Object {$_.type -eq 'node.completed' -and $_.nodeId -eq $case.node -and $_.path -eq 'root'}).Count
  if($count -ne $case.count) { throw "$($case.name): expected $($case.count) root $($case.node) executions, got $count" }
  if($case.name -eq 'tool' -and $run.answer -notmatch '599') { throw '工具回答缺少实际试算金额599。' }
  if($case.name -eq 'report' -and $run.answer -notmatch '1286000|1,286,000|128.6') { throw '报表回答缺少实际营收。' }
  $export=Invoke-RestMethod "$base/api/workflow/runs/$($run.id)/export"
  if($export.events.Count -ne $run.events.Count) { throw '导出事件数量不一致。' }
  $seqs=@($run.events|ForEach-Object {$_.seq})
  if(($seqs|Sort-Object -Unique).Count -ne $seqs.Count) { throw '事件序号存在重复。' }
  $results+=@{name=$case.name;id=$run.id;status=$run.status;mode=$Mode;events=$seqs.Count;models=@($run.events|Where-Object {$_.type -eq 'model.completed'}).Count;executions=$count}
  Write-Host "PASS $($case.name) status=$($run.status) events=$($seqs.Count) id=$($run.id)"
}
if($OutputPath) { $parent=Split-Path -Parent $OutputPath; if($parent){New-Item -ItemType Directory -Force $parent|Out-Null}; $results|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8 $OutputPath }
Write-Host "WORKFLOW_VERIFIED=$($results.Count)/$($cases.Count) mode=$Mode"
