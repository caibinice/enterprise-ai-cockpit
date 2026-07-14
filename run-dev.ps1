param(
  [switch]$SkipInstall,
  [switch]$SkipPostgres,
  [switch]$UseMemory
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'
$flywayEnabled = if ($env:FLYWAY_ENABLED) { $env:FLYWAY_ENABLED } else { 'false' }
$repositoryMode = if ($UseMemory) { 'memory' } elseif ($env:APP_REPOSITORY_MODE) { $env:APP_REPOSITORY_MODE } elseif ($env:MYSQL_PASSWORD) { 'mysql' } else { 'memory' }
$vectorEnabled = if ($env:VECTOR_ENABLED) { $env:VECTOR_ENABLED } else { 'false' }

if (-not $SkipPostgres) {
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

$shell = (Get-Command pwsh -ErrorAction SilentlyContinue)?.Source
if (-not $shell) { $shell = (Get-Command powershell -ErrorAction Stop).Source }

Start-Process $shell -ArgumentList '-NoLogo','-NoProfile','-NoExit','-Command',"Set-Location -LiteralPath '$backend'; `$env:APP_REPOSITORY_MODE='$repositoryMode'; `$env:VECTOR_ENABLED='$vectorEnabled'; `$env:FLYWAY_ENABLED='$flywayEnabled'; mvn spring-boot:run" -WindowStyle Normal
Start-Sleep -Seconds 8
Start-Process $shell -ArgumentList '-NoLogo','-NoProfile','-NoExit','-Command',"Set-Location -LiteralPath '$frontend'; npm run dev" -WindowStyle Normal
Write-Host "Backend:  http://localhost:8080 (repository=$repositoryMode, vector=$vectorEnabled)"
Write-Host 'Frontend: http://localhost:5173'
