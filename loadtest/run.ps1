# Runs the transfer load test for each lock strategy at low and high contention and prints a results table.
# Each strategy starts from a fresh database (the compose volume is deleted) so both are measured on equal terms.
# Usage (from the repo root, Docker Desktop running, close other heavy programs first):
#   ./loadtest/run.ps1                      # 60s per run
#   ./loadtest/run.ps1 -Duration 30s -Vus 16
param(
    [string]$Duration = "60s",
    [int]$Vus = 32,
    [int[]]$WalletCounts = @(1000, 2),
    [string[]]$Strategies = @("PESSIMISTIC", "CONDITIONAL_UPDATE")
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force loadtest/results | Out-Null
$rows = @()
$adminKey = if ($env:LEDGERX_ADMIN_KEY) { $env:LEDGERX_ADMIN_KEY } else { "dev-admin-key" }
$appPort = if ($env:APP_PORT) { $env:APP_PORT } else { "8081" }

foreach ($strategy in $Strategies) {
    Write-Host "== Fresh stack with lock strategy $strategy"
    $env:LOCK_STRATEGY = $strategy
    docker compose --profile monitoring --profile loadtest down -v --remove-orphans
    docker compose up -d --build app
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed - is Docker Desktop healthy?" }

    foreach ($wallets in $WalletCounts) {
        $name = "$strategy-$wallets"
        $file = "loadtest/results/$name.json"
        Remove-Item $file -ErrorAction SilentlyContinue
        Write-Host "== Running $name for $Duration with $Vus VUs"
        $env:WALLETS = $wallets
        $env:VUS = $Vus
        $env:DURATION = $Duration
        docker compose --profile loadtest run --rm k6 run /loadtest/transfers.js --summary-export "/loadtest/results/$name.json"
        # k6 exits non-zero when a threshold fails; keep going so the other runs still happen.

        if (-not (Test-Path $file)) {
            Write-Warning "No results for $name (Docker or the app failed during the run). Skipping."
            continue
        }
        $summary = Get-Content $file -Raw | ConvertFrom-Json
        $trend = $summary.metrics.'http_req_duration{name:transfer}'
        $failed = $summary.metrics.'http_req_failed{name:transfer}'
        $rows += [pscustomobject]@{
            Strategy   = $strategy
            Wallets    = $wallets
            Throughput = "{0:N0} transfers/s" -f $summary.metrics.iterations.rate
            p50_ms     = "{0:N1}" -f $trend.med
            p95_ms     = "{0:N1}" -f $trend.'p(95)'
            p99_ms     = "{0:N1}" -f $trend.'p(99)'
            max_ms     = "{0:N0}" -f $trend.max
            Failed     = "{0:P2}" -f $failed.value
        }
    }

    Write-Host "== Reconciliation after $strategy runs (must report consistent=true)"
    try {
        $report = Invoke-RestMethod -Uri "http://localhost:$appPort/admin/reconciliation" -Headers @{ "X-Admin-Key" = $adminKey }
        Write-Host ("consistent={0} discrepancies={1} totalBalance={2}" -f $report.consistent, $report.discrepancies, $report.totalBalance)
    } catch {
        Write-Warning "Could not run reconciliation: $_"
    }
}

Write-Host ""
Write-Host "Results (copy into docs/BENCHMARKS.md). A run with Failed > 0 or max_ms near 30000 is not clean - rerun it."
$rows | Format-Table -AutoSize
