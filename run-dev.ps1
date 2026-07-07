param(
  [switch]$SkipInstall,
  [switch]$SkipPostgres
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$backend = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'

if (-not $SkipPostgres) {
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    Push-Location $root
    docker compose up -d postgres
    Pop-Location
  } else {
    Write-Warning 'Docker not found. Start PostgreSQL/pgvector manually, or run with APP_REPOSITORY_MODE=memory for the MVP mock repository.'
  }
}

if (-not $SkipInstall) {
  Push-Location $frontend
  npm install
  Pop-Location
}

Start-Process powershell -ArgumentList '-NoExit','-Command',"cd '$backend'; `$env:FLYWAY_ENABLED='true'; mvn spring-boot:run" -WindowStyle Normal
Start-Sleep -Seconds 8
Start-Process powershell -ArgumentList '-NoExit','-Command',"cd '$frontend'; npm run dev" -WindowStyle Normal
Write-Host 'Backend:  http://localhost:8080'
Write-Host 'Frontend: http://localhost:5173'
