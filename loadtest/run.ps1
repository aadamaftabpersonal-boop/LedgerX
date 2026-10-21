# Runs the transfer load test for each lock strategy at low and high contention, several times each,
# and prints median and min-max so a difference can be judged against run-to-run noise.
# Each strategy starts from a fresh database (the compose volume is deleted) so both are measured on equal terms.
#
# Usage (from the repo root, Docker Desktop running, close other heavy programs first):
#   ./loadtest/run.ps1                              # 3 runs of 60s per configuration (about 25 minutes)
#   ./loadtest/run.ps1 -Repeats 1 -Duration 15s    # quick smoke test; do not quote these numbers
param(
    [string]$Duration = "60s",
    [int]$Vus = 32,
    [int]$Repeats = 3,
    [int[]]$WalletCounts = @(1000, 2),
    [string[]]$Strategies = @("PESSIMISTIC", "CONDITIONAL_UPDATE")
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force loadtest/results | Out-Null
$adminKey = if ($env:LEDGERX_ADMIN_KEY) { $env:LEDGERX_ADMIN_KEY } else { "dev-admin-key" }
$appPort = if ($env:APP_PORT) { $env:APP_PORT } else { "8081" }

function Get-Median($values) {
    $sorted = @($values | Sort-Object)
    $n = $sorted.Count
    if ($n -eq 0) { return $null }
    if ($n % 2 -eq 1) { return $sorted[[int](($n - 1) / 2)] }
    return ($sorted[[int]($n / 2 - 1)] + $sorted[[int]($n / 2)]) / 2
}

function Format-Range($values, $format) {
    $min = ($values | Measure-Object -Minimum).Minimum
    $max = ($values | Measure-Object -Maximum).Maximum
    return ("{0} ({1}-{2})" -f ($format -f (Get-Median $values)), ($format -f $min), ($format -f $max))
}

$rows = @()

foreach ($strategy in $Strategies) {
    Write-Host "== Fresh stack with lock strategy $strategy"
    $env:LOCK_STRATEGY = $strategy
    $env:RATE_LIMIT_ENABLED = "false"   # one client sends all the load; the limiter is not what is being measured
    docker compose --profile monitoring --profile loadtest down -v --remove-orphans
    docker compose up -d --build app
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed - is Docker Desktop healthy?" }

    foreach ($wallets in $WalletCounts) {
        $throughput = @(); $p50 = @(); $p95 = @(); $p99 = @(); $failed = @()

        for ($run = 1; $run -le $Repeats; $run++) {
            $name = "$strategy-$wallets-run$run"
            $file = "loadtest/results/$name.json"
            Remove-Item $file -ErrorAction SilentlyContinue
            Write-Host "== $name ($Duration, $Vus VUs)"
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
            $throughput += $summary.metrics.iterations.rate
            $p50 += $trend.med
            $p95 += $trend.'p(95)'
            $p99 += $trend.'p(99)'
            $failed += $summary.metrics.'http_req_failed{name:transfer}'.value
        }

        if ($throughput.Count -gt 0) {
            $rows += [pscustomobject]@{
                Strategy   = $strategy
                Wallets    = $wallets
                Runs       = $throughput.Count
                Throughput = (Format-Range $throughput "{0:N0}") + " /s"
                p50_ms     = Format-Range $p50 "{0:N1}"
                p95_ms     = Format-Range $p95 "{0:N1}"
                p99_ms     = Format-Range $p99 "{0:N1}"
                MaxFailed  = "{0:P2}" -f (($failed | Measure-Object -Maximum).Maximum)
            }
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
Write-Host "Results: median (min-max) over $Repeats run(s). Copy into docs/BENCHMARKS.md."
Write-Host "Treat a gap between strategies as real only if the ranges do not overlap."
$rows | Format-Table -AutoSize -Wrap
