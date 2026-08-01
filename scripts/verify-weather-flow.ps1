param(
  [string]$ProjectRoot = 'D:\codes\ai-agent-rag-demo',
  [int]$Port = 18080,
  [string[]]$Models = @('deepseek-v4-flash', 'deepseek-v4-pro')
)

$ErrorActionPreference = 'Stop'

foreach ($required in @('OPENAI_API_KEY', 'OPENAI_BASE_URL', 'ACTION_PASSWORD', 'AMAP_MAPS_API_KEY')) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($required))) {
    throw "$required must be supplied through the process environment."
  }
}
if (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue) {
  throw "Local port $Port is already in use."
}

$backend = Join-Path $ProjectRoot 'backend'
$weatherServer = Join-Path $backend 'mcp-servers\weather-mcp-server.js'
$amapServer = Join-Path $backend 'mcp-servers\amap-mcp-server.js'
$deployDirectory = Join-Path $ProjectRoot '.deploy'
$stdout = Join-Path $deployDirectory 'local-agent-weather-e2e.out.log'
$stderr = Join-Path $deployDirectory 'local-agent-weather-e2e.err.log'
New-Item -ItemType Directory -Path $deployDirectory -Force | Out-Null
Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

$env:APP_REPOSITORY_MODE = 'memory'
$env:VECTOR_ENABLED = 'false'
$env:FLYWAY_ENABLED = 'false'
$env:LLM_ENABLED = 'true'
$env:LLM_PROVIDER = 'openai-compatible'
$env:LLM_MODEL = $Models[0]
$env:MCP_ENABLED = 'true'
$env:MCP_NODE_COMMAND = (Get-Command node -ErrorAction Stop).Source
$env:MCP_WEATHER_SERVER = (Resolve-Path $weatherServer).Path
$env:MCP_AMAP_SERVER = (Resolve-Path $amapServer).Path
$env:MCP_REQUEST_TIMEOUT = '45s'
$env:ACTION_TOKEN_SECRET = if ($env:ACTION_TOKEN_SECRET) {
  $env:ACTION_TOKEN_SECRET
} else {
  [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
}
$env:SERVER_PORT = [string]$Port

function Wait-Health([string]$Url, [int]$Seconds = 90) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  do {
    try {
      return Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 3
    } catch {
      Start-Sleep -Milliseconds 750
    }
  } while ((Get-Date) -lt $deadline)
  throw "Timed out waiting for $Url"
}

function Read-Sse([string]$Content) {
  $events = [System.Collections.Generic.List[string]]::new()
  $dataByEvent = @{}
  $current = ''
  foreach ($line in ($Content -split "`r?`n")) {
    if ($line.StartsWith('event:')) {
      $current = $line.Substring(6).Trim()
      $events.Add($current)
      if (-not $dataByEvent.ContainsKey($current)) {
        $dataByEvent[$current] = [System.Collections.Generic.List[string]]::new()
      }
    } elseif ($line.StartsWith('data:') -and $current) {
      $dataByEvent[$current].Add($line.Substring(5).TrimStart())
    }
  }
  return @{ Events = $events; Data = $dataByEvent }
}

function Invoke-AgentChat(
  [string]$BaseUrl,
  [hashtable]$Headers,
  [string]$Model,
  [string]$Message,
  [string[]]$Tools
) {
  $body = @{
    conversationId = $null
    message = $Message
    model = $Model
    knowledgeBaseIds = @()
    metadataFilter = @{}
    mcpToolIds = $Tools
    enableTools = $true
    enableChart = $false
  } | ConvertTo-Json -Compress -Depth 10
  $response = Invoke-WebRequest `
    -Method Post `
    -Uri "$BaseUrl/chat/stream" `
    -Headers $Headers `
    -ContentType 'application/json' `
    -Body $body `
    -TimeoutSec 300
  return Read-Sse $response.Content
}

function Parse-EventJson($Sse, [string]$Name) {
  if (-not $Sse.Data.ContainsKey($Name)) { return @() }
  return @($Sse.Data[$Name] | ForEach-Object { $_ | ConvertFrom-Json })
}

function Assert-SafeAnswer([string]$Answer, [string]$Scenario) {
  if ($Answer.Length -lt 60 -or $Answer -match '无法|不可用|No matching') {
    throw "$Scenario answer is incomplete or reports a false failure: $Answer"
  }
  if ($Answer -match '(?i)<script|<canvas|cdn\.jsdelivr|document\.getElementById|new Chart\(') {
    throw "$Scenario answer leaked executable chart markup."
  }
}

function Test-ZhejiangAgent(
  [string]$BaseUrl,
  [hashtable]$Headers,
  [string]$Model
) {
  $sse = Invoke-AgentChat `
    -BaseUrl $BaseUrl `
    -Headers $Headers `
    -Model $Model `
    -Message '罗列浙江省所有地级市今天的天气，并分别展示气温柱状图和湿度折线图' `
    -Tools @('weather', 'amap')
  foreach ($required in @('meta', 'plan', 'tool', 'token', 'references', 'chart', 'done')) {
    if (-not ($sse.Events -contains $required)) {
      throw "$Model Zhejiang scenario is missing SSE event: $required"
    }
  }

  $traces = Parse-EventJson $sse 'tool'
  $amap = @($traces | Where-Object { $_.id -eq 'amap' -and $_.status -eq 'success' })
  $weatherTrace = @($traces | Where-Object { $_.id -eq 'weather' -and $_.status -eq 'success' }) | Select-Object -Last 1
  if (-not $amap.Count) { throw "$Model did not use Amap to resolve Zhejiang cities." }
  if (-not $weatherTrace) { throw "$Model did not call the weather MCP after Amap." }
  $weather = $weatherTrace.output | ConvertFrom-Json
  if ($weather.count -ne 11 -or $weather.cities.Count -ne 11) {
    throw "$Model did not return all 11 Zhejiang prefecture-level cities."
  }

  $charts = Parse-EventJson $sse 'chart'
  if ($charts.Count -lt 2) { throw "$Model returned fewer than two requested charts." }
  $types = @($charts | ForEach-Object { $_.series[0].type })
  if (-not ($types -contains 'bar') -or -not ($types -contains 'line')) {
    throw "$Model charts do not include both bar and line types."
  }
  $temperatureChart = $charts | Where-Object { $_.title.text -match '气温|温度' } | Select-Object -First 1
  if (-not $temperatureChart -or $temperatureChart.xAxis.data.Count -ne 11) {
    throw "$Model temperature chart does not contain 11 cities."
  }
  if (($temperatureChart.xAxis.data -join ',') -ne ($weather.cities.city -join ',')) {
    throw "$Model temperature chart categories do not match MCP weather data."
  }
  for ($index = 0; $index -lt 11; $index += 1) {
    $item = $temperatureChart.series[0].data[$index]
    $chartValue = if ($null -ne $item.value) { [double]$item.value } else { [double]$item }
    if ($chartValue -ne [double]$weather.cities[$index].temperatureC) {
      throw "$Model temperature chart value $index does not match MCP data."
    }
  }

  $references = Parse-EventJson $sse 'references'
  if ($references.Count -ne 0) { throw "$Model returned unrelated references for weather-only chat." }
  $answer = $sse.Data.token -join ''
  Assert-SafeAnswer $answer "$Model Zhejiang scenario"
  return [pscustomobject]@{
    model = $Model
    plan = (Parse-EventJson $sse 'plan')[0].summary
    toolSequence = ($traces.id -join ',')
    cityCount = $weather.count
    chartCount = $charts.Count
    chartTypes = $types -join ','
    references = $references.Count
    answerLength = $answer.Length
  }
}

function Test-CountryAgent(
  [string]$BaseUrl,
  [hashtable]$Headers,
  [string]$Model
) {
  $sse = Invoke-AgentChat `
    -BaseUrl $BaseUrl `
    -Headers $Headers `
    -Model $Model `
    -Message '请列出日本主要城市今天的天气，按气温从高到低总结，不需要图表' `
    -Tools @('weather')
  $traces = Parse-EventJson $sse 'tool'
  $weatherTrace = @($traces | Where-Object { $_.id -eq 'weather' -and $_.status -eq 'success' }) | Select-Object -Last 1
  if (-not $weatherTrace) { throw "$Model did not plan a weather call for a country-level request." }
  $weather = $weatherTrace.output | ConvertFrom-Json
  if ($weather.count -lt 5 -or $weather.count -gt 20) {
    throw "$Model country scope returned an unreasonable city count: $($weather.count)"
  }
  $chartCount = (Parse-EventJson $sse 'chart').Count
  if ($chartCount -ne 0) {
    throw "$Model ignored the explicit no-chart instruction."
  }
  $answer = $sse.Data.token -join ''
  Assert-SafeAnswer $answer "$Model country scenario"
  return [pscustomobject]@{
    model = $Model
    cityCount = $weather.count
    region = $weather.region
    chartCount = $chartCount
    answerLength = $answer.Length
  }
}

$process = Start-Process `
  -FilePath (Get-Command pwsh -ErrorAction Stop).Source `
  -ArgumentList @('-NoLogo', '-NoProfile', '-Command', 'mvn spring-boot:run') `
  -WorkingDirectory $backend `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr `
  -WindowStyle Hidden `
  -PassThru

try {
  $baseUrl = "http://127.0.0.1:$Port/api"
  $health = Wait-Health "$baseUrl/health"
  $options = Invoke-RestMethod -Method Get -Uri "$baseUrl/chat/options" -TimeoutSec 10
  foreach ($tool in @('weather', 'amap')) {
    if (-not ($options.mcpTools.id -contains $tool)) { throw "Missing MCP option: $tool" }
  }
  $auth = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/action-auth/verify" `
    -ContentType 'application/json' `
    -Body (@{ password = $env:ACTION_PASSWORD } | ConvertTo-Json -Compress) `
    -TimeoutSec 10
  $headers = @{ Authorization = "Bearer $($auth.token)" }
  $zhejiang = @($Models | ForEach-Object {
    Test-ZhejiangAgent -BaseUrl $baseUrl -Headers $headers -Model $_
  })
  $country = Test-CountryAgent -BaseUrl $baseUrl -Headers $headers -Model $Models[0]

  [pscustomobject]@{
    healthMode = $health.mode
    mcpEnabled = $options.mcpEnabled
    zhejiang = $zhejiang
    country = $country
  } | ConvertTo-Json -Depth 8
} catch {
  $failure = $_
  Write-Warning "Agent weather verification failed: $($failure.Exception.Message)"
  if (Test-Path $stderr) { Write-Warning ((Get-Content $stderr -Tail 100) -join "`n") }
  if (Test-Path $stdout) { Write-Warning ((Get-Content $stdout -Tail 160) -join "`n") }
  throw $failure
} finally {
  if ($process -and -not $process.HasExited) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
  }
  Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}
