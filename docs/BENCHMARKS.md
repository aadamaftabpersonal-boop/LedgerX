# Benchmarks

> **Status: the tables below were measured at v0.4, before reversals, the transactional outbox and rate limiting
> existed.** The outbox adds one more insert (plus JSON serialisation) to every transfer, so current throughput will
> be somewhat lower. The runner now repeats each configuration and reports median and range; run it to refresh the
> numbers before quoting any of them.

Measured on one laptop with the app, PostgreSQL and the load generator all running on it. These numbers show how the
two lock strategies behave relative to each other and to contention. They are not a statement of production capacity.

## How to reproduce

```powershell
# Docker Desktop running; close other heavy programs first.
./loadtest/run.ps1                        # 3 runs of 60 s per configuration (about 25 minutes)
./loadtest/run.ps1 -Repeats 1 -Duration 15s   # quick smoke test; do not quote these numbers
```

The table printed at the end shows **median (min-max)** over the repeats. Treat a gap between the two strategies as
real only if their ranges do not overlap; otherwise it is within run-to-run noise.

The script starts a fresh stack (new database volume) per lock strategy, runs k6 at two contention levels, and checks
reconciliation afterwards. Rate limiting is switched off for these runs because a single client sends all the load.

- **1000 wallets (low contention):** each transfer picks two random wallets out of 1000, so rows are rarely shared.
- **2 wallets (high contention):** every transfer fights over the same two rows.
- Each transfer moves 1 paisa between wallets funded with 10,000,000 INR, so no request is rejected for insufficient funds.
- Latency is the full HTTP request time seen by k6 (`http_req_duration{name:transfer}`), including auth and the database transaction.

## Environment

| | |
|---|---|
| CPU | 13th Gen Intel Core i7-13650HX, 14 cores / 20 threads |
| RAM | (fill in) |
| OS / Docker | Windows 11, Docker Desktop 4.81 (WSL2 backend) |
| App | Spring Boot 3.5, Java 21, Hikari pool of 20 connections |
| Database | PostgreSQL 16 in Docker, default settings |
| Load generator | k6 0.54 in Docker, 32 virtual users, 60 s per run, same machine |

## Results at v0.4 (one 60 s run per row, before the outbox)

| Strategy | Wallets | Throughput | p50 | p95 | p99 | max | Failed |
|---|---|---|---|---|---|---|---|
| PESSIMISTIC | 1000 | 2,224 transfers/s | 12.1 ms | 18.5 ms | 23.9 ms | 108 ms | 0.00% |
| CONDITIONAL_UPDATE | 1000 | 2,066 transfers/s | 12.9 ms | 19.4 ms | 26.5 ms | 151 ms | 0.00% |
| PESSIMISTIC | 2 | 340 transfers/s | 60.4 ms | 250.4 ms | 401.0 ms | 1,282 ms | 0.00% |
| CONDITIONAL_UPDATE | 2 | 351 transfers/s | 44.5 ms | 274.8 ms | 439.5 ms | 934 ms | 0.00% |

Reconciliation after the `CONDITIONAL_UPDATE` runs: `consistent = true`, `discrepancies = 0`, `totalBalance = 0`.
(The script prints the same check after the `PESSIMISTIC` runs; confirm that line before quoting it.)

## What the numbers show

**Contention is the dominant factor, not the lock strategy.** Going from 1000 wallets to 2 cut throughput by roughly
6x (about 2,200 down to about 345 transfers/s) and raised p99 latency by roughly 17x (24-27 ms to 400-440 ms) for
both strategies. When every request needs the same two rows, transfers queue behind each other.

**The two strategies are indistinguishable here.**
- At low contention `PESSIMISTIC` was about 8% faster (2,224 vs 2,066).
- At high contention `CONDITIONAL_UPDATE` was about 3% faster (351 vs 340).
- Each cell is a single run, and the sign of the difference flips between the two contention levels, so these gaps are
  within run-to-run noise. Repeating each configuration several times would be needed to claim any difference.

**A 15-second smoke test pointed the other way and was wrong.** It showed `CONDITIONAL_UPDATE` 20% faster at low
contention and 47% faster at high contention. That gap disappeared in the 60-second runs, which include JVM warm-up
for a smaller share of the time. This is why short runs are not used for conclusions.

**The numbers are internally consistent.** With 32 users issuing requests back to back, average latency should be
about 32 / throughput. For the 2-wallet runs that is 32 / 340 = 94 ms, and the measured average was about 91 ms.

**Likely explanation for why the strategies tie (a hypothesis, not measured).** Both strategies hold the row locks
until the transaction commits, and a commit waits for the write-ahead log flush. At high contention the throughput
(about 345/s means about 2.9 ms per transaction, one at a time) is probably set by commit latency while holding the
lock. `CONDITIONAL_UPDATE` only saves one lock-acquiring round trip, which does not change that. Confirming this
would need PostgreSQL wait-event data (`pg_stat_activity`) during a run.

## Limitations

- The tables above are single runs with no confidence intervals; `run.ps1` now produces repeated runs with ranges.
- Application, database and load generator share one machine and compete for CPU and disk.
- Default PostgreSQL settings (`synchronous_commit` on); results would differ with tuned durability settings.
- Transfers are uniformly random across wallets. Real traffic is often skewed towards a few hot wallets, which is
  closer to the 2-wallet case than the 1000-wallet case.
