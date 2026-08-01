param(
  [switch]$SkipInstall,
  [switch]$SkipPostgres,
  [switch]$UseMemory,
  [string]$CredentialsPath = '',
  [switch]$ValidateConfigOnly
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$localCredentialsFile = Join-Path $root 'credentials.txt'
$sharedCredentialsFile = Join-Path (Split-Path -Parent $root) 'ai-blog\credentials.txt'
$credentialsFile = if (-not [string]::IsNullOrWhiteSpace($CredentialsPath)) {
  (Resolve-Path -LiteralPath $CredentialsPath).Path
} elseif (Test-Path -LiteralPath $localCredentialsFile) {
  $localCredentialsFile
} elseif (Test-Path -LiteralPath $sharedCredentialsFile) {
  $sharedCredentialsFile
} else {
  $null
}
$sharedCredentialsFullPath = [IO.Path]::GetFullPath($sharedCredentialsFile)
$credentialSectionPrefix = if ($null -ne $credentialsFile -and [IO.Path]::GetFullPath($credentialsFile) -eq $sharedCredentialsFullPath) { 'cockpit.' } else { '' }
$usingConfiguredDatabases = $false

function Import-IniFile {
  param([Parameter(Mandatory = $true)][string]$Path)
  $result = @{}
  $section = ''
  foreach ($original in Get-Content -LiteralPath $Path -Encoding UTF8) {
    $line = $original.Trim()
    if (-not $line -or $line.StartsWith('#') -or $line.StartsWith(';')) { continue }
    if ($line -match '^\[(.+)\]$') {
      $section = $Matches[1].Trim().ToLowerInvariant()
      if (-not $result.ContainsKey($section)) { $result[$section] = @{} }
      continue
    }
    $parts = $line.Split('=', 2)
    if ($parts.Count -eq 2 -and $section) {
      $result[$section][$parts[0].Trim().ToLowerInvariant()] = $parts[1].Trim()
    }
  }
  return $result
}

function Get-CredentialSection {
  param(
    [Parameter(Mandatory = $true)]$Credentials,
    [Parameter(Mandatory = $true)][string]$Name
  )
  foreach ($candidate in @("$credentialSectionPrefix$Name", $Name)) {
    $normalized = $candidate.ToLowerInvariant()
    if ($Credentials.ContainsKey($normalized)) { return $Credentials[$normalized] }
  }
  return $null
}

function Set-DefaultEnv {
  param([string]$Name, [string]$Value)
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process')) -and -not [string]::IsNullOrWhiteSpace($Value)) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
  }
}

if ($null -ne $credentialsFile -and -not $UseMemory) {
  $credentialMap = Import-IniFile -Path $credentialsFile
  if ($credentialMap.ContainsKey('cockpit.mysql.remote')) {
    $credentialSectionPrefix = 'cockpit.'
  }
  $mysql = Get-CredentialSection $credentialMap 'mysql.remote'
  $vector = Get-CredentialSection $credentialMap 'postgresql.vector'
  $llm = Get-CredentialSection $credentialMap 'deepseek.api'
  $amap = Get-CredentialSection $credentialMap 'amap.api'
  $embedding = Get-CredentialSection $credentialMap 'embedding'
  $action = Get-CredentialSection $credentialMap 'platform.action'
  if ($null -ne $mysql -and $null -ne $vector) {
    $mysqlPort = if ($mysql.ContainsKey('port')) { $mysql['port'] } else { '3306' }
    $vectorPort = if ($vector.ContainsKey('port')) { $vector['port'] } else { '5432' }
    Set-DefaultEnv 'APP_REPOSITORY_MODE' 'mysql'
    Set-DefaultEnv 'MYSQL_URL' "jdbc:mysql://$($mysql['host']):$mysqlPort/$($mysql['database'])?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    Set-DefaultEnv 'MYSQL_USER' $mysql['user']
    Set-DefaultEnv 'MYSQL_PASSWORD' $mysql['password']
    Set-DefaultEnv 'VECTOR_ENABLED' 'true'
    Set-DefaultEnv 'VECTOR_DATABASE_URL' "jdbc:postgresql://$($vector['host']):$vectorPort/$($vector['database'])"
    Set-DefaultEnv 'VECTOR_DATABASE_USER' $vector['user']
    Set-DefaultEnv 'VECTOR_DATABASE_PASSWORD' $vector['password']
    $usingConfiguredDatabases = $true
  }
  if ($null -ne $llm) {
    Set-DefaultEnv 'LLM_ENABLED' 'true'
    Set-DefaultEnv 'LLM_PROVIDER' 'openai-compatible'
    Set-DefaultEnv 'OPENAI_BASE_URL' $llm['base-url']
    Set-DefaultEnv 'OPENAI_API_KEY' $llm['api-key']
    Set-DefaultEnv 'DEEPSEEK_API_KEY' $llm['api-key']
    Set-DefaultEnv 'LLM_MODEL' $llm['model']
  }
  if ($null -ne $amap) {
    Set-DefaultEnv 'AMAP_MAPS_API_KEY' $amap['api-key']
    Set-DefaultEnv 'MCP_ENABLED' 'true'
  }
  if ($null -ne $embedding -and $embedding.ContainsKey('dimensions')) {
    Set-DefaultEnv 'EMBEDDING_DIMENSIONS' $embedding['dimensions']
  }
  if ($null -ne $action) { Set-DefaultEnv 'ACTION_PASSWORD' $action['password'] }
}

$flywayEnabled = if ($env:FLYWAY_ENABLED) { $env:FLYWAY_ENABLED } else { 'false' }
$repositoryMode = if ($UseMemory) { 'memory' } elseif ($env:APP_REPOSITORY_MODE) { $env:APP_REPOSITORY_MODE } elseif ($env:MYSQL_PASSWORD) { 'mysql' } else { 'memory' }
$vectorEnabled = if ($env:VECTOR_ENABLED) { $env:VECTOR_ENABLED } else { 'false' }

if ($ValidateConfigOnly) {
  $requiredEnvironment = @(
    'MYSQL_URL', 'MYSQL_USER', 'MYSQL_PASSWORD',
    'VECTOR_DATABASE_URL', 'VECTOR_DATABASE_USER', 'VECTOR_DATABASE_PASSWORD',
    'OPENAI_BASE_URL', 'OPENAI_API_KEY', 'AMAP_MAPS_API_KEY', 'ACTION_PASSWORD'
  )
  $missingEnvironment = @(
    $requiredEnvironment | Where-Object {
      [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
    }
  )
  if ($missingEnvironment.Count) {
    throw "座舱配置缺少环境映射：$($missingEnvironment -join ', ')"
  }
  Write-Host "Configuration ready: repository=$repositoryMode vector=$vectorEnabled source=$credentialsFile (values hidden)" -ForegroundColor Green
  return
}

if (-not $SkipPostgres -and -not $usingConfiguredDatabases) {
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    Push-Location $root
    docker compose up -d mysql postgres
    Pop-Location
  } else {
    Write-Warning 'Docker not found. Set MYSQL_*/VECTOR_* manually for real databases, or use -UseMemory.'
  }
}

if (-not $SkipInstall) {
  Push-Location $frontend
  npm ci
  Pop-Location
}

$shell = (Get-Command pwsh -ErrorAction Stop).Source

Start-Process $shell -ArgumentList '-NoLogo','-NoProfile','-NoExit','-Command',"Set-Location -LiteralPath '$backend'; `$env:APP_REPOSITORY_MODE='$repositoryMode'; `$env:VECTOR_ENABLED='$vectorEnabled'; `$env:FLYWAY_ENABLED='$flywayEnabled'; mvn spring-boot:run" -WindowStyle Normal
Start-Sleep -Seconds 8
Start-Process $shell -ArgumentList '-NoLogo','-NoProfile','-NoExit','-Command',"Set-Location -LiteralPath '$frontend'; npm run dev" -WindowStyle Normal
Write-Host "Backend:  http://localhost:8080 (repository=$repositoryMode, vector=$vectorEnabled)"
Write-Host 'Frontend: http://localhost:5173'
