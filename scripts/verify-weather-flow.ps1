param(
  [string]$ProjectRoot = 'D:\codes\ai-agent-rag-demo',
  [int]$Port = 18080,
  [string[]]$Models = @('deepseek-v4-flash', 'deepseek-v4-pro')
)

$ErrorActionPreference = 'Stop'

if (-not $env:OPENAI_API_KEY) {
  throw 'OPENAI_API_KEY must be supplied through the process environment.'
}
if (-not $env:OPENAI_BASE_URL) {
  throw 'OPENAI_BASE_URL must be supplied through the process environment.'
}
if (-not $env:ACTION_PASSWORD) {
  throw 'ACTION_PASSWORD must be supplied through the process environment.'
}
if (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue) {
  throw "Local port $Port is already in use."
}

$backend = Join-Path $ProjectRoot 'backend'
$weatherServer = Join-Path $backend 'mcp-servers\weather-mcp-server.js'
$deployDirectory = Join-Path $ProjectRoot '.deploy'
$stdout = Join-Path $deployDirectory 'local-weather-e2e.out.log'
$stderr = Join-Path $deployDirectory 'local-weather-e2e.err.log'
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

function Test-WeatherModel(
  [string]$BaseUrl,
  [hashtable]$Headers,
  [string]$Model
) {
  $body = @{
    conversationId = $null
    message = '江苏所有城市今天的天气，并且给我展示各城市温度的对比柱状图'
    model = $Model
    knowledgeBaseIds = @()
    metadataFilter = @{}
    mcpToolIds = @('weather')
    enableTools = $true
    enableChart = $false
  } | ConvertTo-Json -Compress -Depth 8
  $response = Invoke-WebRequest `
    -Method Post `
    -Uri "$BaseUrl/chat/stream" `
    -Headers $Headers `
    -ContentType 'application/json' `
    -Body $body `
    -TimeoutSec 180
  $sse = Read-Sse $response.Content
  foreach ($required in @('meta', 'tool', 'token', 'references', 'chart', 'done')) {
    if (-not ($sse.Events -contains $required)) {
      throw "$Model is missing SSE event: $required"
    }
  }

  $trace = ($sse.Data.tool -join "`n") | ConvertFrom-Json
  if ($trace.status -ne 'success') {
    throw "$Model weather tool status is $($trace.status): $($trace.output)"
  }
  $weather = $trace.output | ConvertFrom-Json
  $chart = ($sse.Data.chart -join "`n") | ConvertFrom-Json
  $references = @(($sse.Data.references -join "`n") | ConvertFrom-Json)
  $answer = $sse.Data.token -join ''

  if ($weather.count -ne 13 -or $weather.cities.Count -ne 13) {
    throw "$Model did not return all 13 Jiangsu cities."
  }
  if ($chart.series[0].name -ne '实时气温' -or $chart.xAxis.data.Count -ne 13) {
    throw "$Model did not return a 13-city real-temperature chart."
  }
  if (($chart.xAxis.data -join ',') -ne ($weather.cities.city -join ',')) {
    throw "$Model chart cities do not match MCP cities."
  }
  for ($index = 0; $index -lt 13; $index += 1) {
    if (
      [double]$chart.series[0].data[$index].value `
        -ne [double]$weather.cities[$index].temperatureC
    ) {
      throw "$Model chart temperature at index $index does not match MCP data."
    }
  }
  if ($references.Count -ne 0) {
    throw "$Model returned unrelated knowledge-base references for a weather-only request."
  }
  if ($answer.Length -lt 80 -or $answer -match '无法|不可用') {
    throw "$Model answer is incomplete or reports a false tool failure: $answer"
  }

  return [pscustomobject]@{
    model = $Model
    events = ($sse.Events | Select-Object -Unique) -join ','
    cityCount = $weather.count
    chartPoints = $chart.series[0].data.Count
    references = $references.Count
    answerLength = $answer.Length
    minimumC = ($weather.cities.temperatureC | Measure-Object -Minimum).Minimum
    maximumC = ($weather.cities.temperatureC | Measure-Object -Maximum).Maximum
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
  if (-not $options.mcpEnabled -or -not ($options.mcpTools.id -contains 'weather')) {
    throw 'Weather MCP is not available in chat options.'
  }

  $auth = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/action-auth/verify" `
    -ContentType 'application/json' `
    -Body (@{ password = $env:ACTION_PASSWORD } | ConvertTo-Json -Compress) `
    -TimeoutSec 10
  $headers = @{ Authorization = "Bearer $($auth.token)" }
  $results = @($Models | ForEach-Object {
    Test-WeatherModel -BaseUrl $baseUrl -Headers $headers -Model $_
  })

  [pscustomobject]@{
    healthMode = $health.mode
    mcpEnabled = $options.mcpEnabled
    results = $results
  } | ConvertTo-Json -Depth 6
} catch {
  $failure = $_
  Write-Warning "Weather flow verification failed: $($failure.Exception.Message)"
  if (Test-Path $stderr) {
    Write-Warning ((Get-Content $stderr -Tail 80) -join "`n")
  }
  if (Test-Path $stdout) {
    Write-Warning ((Get-Content $stdout -Tail 120) -join "`n")
  }
  throw $failure
} finally {
  if ($process -and -not $process.HasExited) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
  }
  Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}
