[CmdletBinding()]
param(
  [string]$BaseUrl = 'https://101.132.78.217/smartCockpit/api'
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw 'This script requires PowerShell 7 (pwsh).'
}
if ([string]::IsNullOrWhiteSpace($env:ACTION_PASSWORD)) {
  throw 'Set ACTION_PASSWORD in the current process before seeding demo data.'
}

$base = $BaseUrl.TrimEnd('/')
$projectRoot = Split-Path -Parent $PSScriptRoot
$verifyBody = @{ password = $env:ACTION_PASSWORD } | ConvertTo-Json
$tokenResponse = Invoke-RestMethod `
  -Uri "$base/action-auth/verify" `
  -Method Post `
  -ContentType 'application/json' `
  -Body $verifyBody `
  -SkipCertificateCheck
$headers = @{ Authorization = "Bearer $($tokenResponse.token)" }

$seed = @(
  @{
    name = '客户服务政策库'
    code = 'CUSTOMER-SERVICE'
    businessType = '客户服务'
    description = '售前咨询、订单履约、退款与服务升级规则。'
    documents = @(
      @{
        title = '退款与售后服务规则'
        metadata = @{ category = 'policy'; business = 'customer-service'; version = '2026.07' }
        content = @'
退款申请应在商品签收后 7 个自然日内提交。商品存在质量问题、错发或运输破损时，客户无需承担退货运费。
普通无理由退货需保持商品、附件与包装完整。客服应在 2 个工作小时内首次响应，并在收到完整凭证后 1 个工作日内给出处理方案。
退款原路返回，审核通过后通常需要 1 至 5 个工作日到账。超过承诺时效的工单应升级给值班主管，并向客户说明新的完成时间。
'@
      }
      @{
        title = '客服分级与 SLA'
        metadata = @{ category = 'operations'; business = 'customer-service'; version = '2026.07' }
        content = @'
咨询类工单为 P3，目标首次响应时间 2 个工作小时；订单受阻或重复扣款为 P2，目标首次响应时间 30 分钟；
大面积支付故障、数据泄露疑虑或舆情风险为 P1，应在 10 分钟内通知值班负责人。任何等级都不得在知识证据不足时承诺赔付金额。
'@
      }
      @{
        title = 'AI 客服知识库建设与证据回答规范'
        path = 'demo-data/knowledge/customer-service/ai-customer-service-rag-guidelines.md'
        metadata = @{ category = 'knowledge-governance'; business = 'customer-service'; version = '2026.07'; source = 'ai-blog/enterprise-ai-cockpit'; language = 'zh-CN' }
      }
      @{
        title = '订单异常与物流延迟处置手册'
        path = 'demo-data/knowledge/customer-service/order-exception-logistics-playbook.md'
        metadata = @{ category = 'operations'; business = 'customer-service'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
      @{
        title = '投诉升级、舆情与隐私事件应急流程'
        path = 'demo-data/knowledge/customer-service/complaint-privacy-escalation.md'
        metadata = @{ category = 'risk'; business = 'customer-service'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
      @{
        title = '多语言跨境客服沟通与质检指南'
        path = 'demo-data/knowledge/customer-service/multilingual-service-quality.md'
        metadata = @{ category = 'quality'; business = 'customer-service'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
    )
  }
  @{
    name = '跨境电商经营库'
    code = 'CROSS-BORDER'
    businessType = '跨境电商'
    description = '跨境渠道、履约与示例经营指标。'
    documents = @(
      @{
        title = '跨境业务示例经营日报'
        metadata = @{ category = 'metrics'; business = 'cross-border'; period = '2026-Q2'; environment = 'demo' }
        content = @'
2026 年第二季度示例数据：日本站销售额 120 万元，环比增长 18%；北美站销售额 95 万元，环比增长 9%；
欧洲站销售额 88 万元，环比下降 3%。日本站主要增长来自家居收纳与户外用品。
欧洲站退货率为 8.4%，高于 6% 的业务预警线，建议优先检查尺码描述、到货时效和包装破损。
'@
      }
      @{
        title = '跨境履约风险清单'
        metadata = @{ category = 'risk'; business = 'cross-border'; version = '2026.07' }
        content = @'
发货前必须校验目的国禁限售规则、税则编码、申报价值和承运商尺寸限制。
预计延迟超过 48 小时时，运营应暂停高风险渠道的广告放量，并主动通知受影响客户。
任何基于示例数据形成的建议都必须标注为演示用途，不得直接替代财务或合规审核。
'@
      }
      @{
        title = '跨境趋势日报的数据口径与证据链'
        path = 'demo-data/knowledge/cross-border/trend-daily-methodology.md'
        metadata = @{ category = 'methodology'; business = 'cross-border'; version = '2026.07'; source = 'ai-blog/cross-border-trends'; language = 'zh-CN' }
      }
      @{
        title = '日本、北美与欧洲站点选品节奏'
        path = 'demo-data/knowledge/cross-border/market-selection-calendar.md'
        metadata = @{ category = 'merchandising'; business = 'cross-border'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
      @{
        title = '库存、税务与跨境履约联动手册'
        path = 'demo-data/knowledge/cross-border/inventory-tax-logistics.md'
        metadata = @{ category = 'supply-chain'; business = 'cross-border'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
      @{
        title = '跨境经营指标诊断与实验复盘'
        path = 'demo-data/knowledge/cross-border/metrics-experiment-review.md'
        metadata = @{ category = 'analytics'; business = 'cross-border'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
    )
  }
  @{
    name = '量化研究治理库'
    code = 'QUANT-RESEARCH'
    businessType = '量化研究'
    description = '研究流程、风险边界与模型上线检查。'
    documents = @(
      @{
        title = '量化策略上线检查表'
        metadata = @{ category = 'governance'; business = 'quant-research'; version = '2026.07' }
        content = @'
策略进入仿真或实盘前，必须完成数据泄漏检查、交易成本与滑点压力测试、样本外验证和最大回撤评估。
任何单一策略的建议风险预算不应超过组合净值的 20%。回测结果必须记录数据版本、参数、代码提交和运行时间。
若实时表现偏离样本外区间，应先停止新增仓位，再进行归因分析；不得通过临时放宽风险阈值掩盖异常。
'@
      }
      @{
        title = '回测可信度与数据泄漏审计清单'
        path = 'demo-data/knowledge/quant-research/backtest-audit-checklist.md'
        metadata = @{ category = 'backtest'; business = 'quant-research'; version = '2026.07'; source = 'ai-blog/trust-the-backtest'; language = 'zh-CN' }
      }
      @{
        title = 'LLM 舆情因子的 Walk-forward 验证方法'
        path = 'demo-data/knowledge/quant-research/llm-sentiment-walk-forward.md'
        metadata = @{ category = 'llm-factor'; business = 'quant-research'; version = '2026.07'; source = 'ai-blog/llm-sentiment-walk-forward'; language = 'zh-CN' }
      }
      @{
        title = '量化研究数据版本与可复现性规范'
        path = 'demo-data/knowledge/quant-research/research-data-versioning.md'
        metadata = @{ category = 'reproducibility'; business = 'quant-research'; version = '2026.07'; source = 'ai-blog/ai-quant-system'; language = 'zh-CN' }
      }
      @{
        title = '风险预算、异常止损与模型下线流程'
        path = 'demo-data/knowledge/quant-research/risk-budget-shutdown.md'
        metadata = @{ category = 'risk'; business = 'quant-research'; version = '2026.07'; source = 'curated-demo'; language = 'zh-CN' }
      }
    )
  }
)

$knowledgeBases = Invoke-RestMethod `
  -Uri "$base/admin/knowledge-bases" `
  -Headers $headers `
  -SkipCertificateCheck
$createdKnowledgeBases = 0
$createdDocuments = 0

foreach ($item in $seed) {
  $knowledgeBase = $knowledgeBases |
    Where-Object { $_.code -eq $item.code } |
    Select-Object -First 1
  if (-not $knowledgeBase) {
    $body = @{
      name = $item.name
      code = $item.code
      businessType = $item.businessType
      description = $item.description
    } | ConvertTo-Json
    $knowledgeBase = Invoke-RestMethod `
      -Uri "$base/admin/knowledge-bases" `
      -Method Post `
      -Headers $headers `
      -ContentType 'application/json' `
      -Body $body `
      -SkipCertificateCheck
    $createdKnowledgeBases++
  }

  $documents = Invoke-RestMethod `
    -Uri "$base/admin/documents?knowledgeBaseId=$($knowledgeBase.id)" `
    -Headers $headers `
    -SkipCertificateCheck
  foreach ($document in $item.documents) {
    if ($documents.title -contains $document.title) {
      continue
    }
    $content = if ($document.ContainsKey('path')) {
      $path = Join-Path $projectRoot $document.path
      if (-not (Test-Path -LiteralPath $path)) {
        throw "Seed document is missing: $path"
      }
      Get-Content -LiteralPath $path -Raw
    } else {
      $document.content
    }
    $body = @{
      title = $document.title
      content = $content.Trim()
      metadata = $document.metadata
    } | ConvertTo-Json -Depth 6
    Invoke-RestMethod `
      -Uri "$base/admin/documents/text?knowledgeBaseId=$($knowledgeBase.id)" `
      -Method Post `
      -Headers $headers `
      -ContentType 'application/json' `
      -Body $body `
      -SkipCertificateCheck | Out-Null
    $createdDocuments++
  }
}

$health = Invoke-RestMethod -Uri "$base/health" -SkipCertificateCheck
Write-Output "CREATED_KNOWLEDGE_BASES=$createdKnowledgeBases"
Write-Output "CREATED_DOCUMENTS=$createdDocuments"
Write-Output "TOTAL_KNOWLEDGE_BASES=$($health.knowledgeBases)"
Write-Output "TOTAL_DOCUMENTS=$($health.documents)"
Write-Output "TOTAL_CHUNKS=$($health.chunks)"
Write-Output "VECTOR_STATUS=$($health.vectorStore)"
