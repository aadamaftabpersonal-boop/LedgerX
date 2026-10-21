# Benchmarks

> **Status:** results below are from the current code (reversals, transactional outbox and rate limiting included;
> rate limiting is switched off for the runs), 3 runs of 60 s per configuration. The v0.4 figures from before the
> outbox are kept further down for comparison.

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

## Results (median, with min-max over 3 runs of 60 s)

| Strategy | Wallets | Throughput | p50 | p95 | p99 | Failed |
|---|---|---|---|---|---|---|
| PESSIMISTIC | 1000 | 1,641 /s (1,359-1,694) | 16.1 ms (15.2-18.8) | 25.1 ms (24.0-30.1) | 32.8 ms (32.6-43.8) | 0.00% |
| CONDITIONAL_UPDATE | 1000 | 1,599 /s (1,175-1,660) | 15.8 ms (15.7-19.4) | 25.3 ms (24.4-36.5) | 35.5 ms (35.1-61.2) | 0.00% |
| PESSIMISTIC | 2 | 172 /s (169-173) | 144.9 ms (144.7-146.3) | 432.5 ms (430.3-442.5) | 634.6 ms (623.6-659.0) | 0.00% |
| CONDITIONAL_UPDATE | 2 | 170 /s (166-173) | 157.3 ms (154.6-159.2) | 402.1 ms (386.5-415.1) | 581.9 ms (571.0-603.0) | 0.00% |

No request failed in any of the 12 runs, and reconciliation after the `CONDITIONAL_UPDATE` runs reported
`consistent = true`, `discrepancies = 0`, `totalBalance = 0`.

## What the numbers show

**Contention is the dominant factor.** Going from 1000 wallets to 2 cut throughput by roughly 9-10x (about 1,600 down
to about 171 transfers/s) and raised p99 latency by roughly 18x (33-36 ms to 580-635 ms) for both strategies. When
every request needs the same two rows, transfers queue behind each other.

**The two strategies are indistinguishable.** In every configuration the min-max ranges overlap:
- At low contention the medians differ by 2.6% (1,641 vs 1,599) and the ranges (1,359-1,694 vs 1,175-1,660) overlap
  almost completely; `CONDITIONAL_UPDATE` has the wider spread.
- At high contention the medians differ by about 1% (172 vs 170) and the ranges (169-173 vs 166-173) overlap.
- The tight high-contention ranges (about 2% wide) make this a firm result, not a lack of data: the two strategies
  really do perform the same here.

**The numbers are internally consistent.** With 32 users issuing requests back to back, average latency should be
about 32 / throughput. For the 2-wallet runs that is 32 / 172 = 186 ms, and the measured average of the last run was
184.7 ms.

**Likely explanation for the tie (a hypothesis, not measured).** Both strategies hold the row locks until the
transaction commits, and a commit waits for the write-ahead log flush. At high contention, throughput is probably set
by commit latency while holding the lock, one transaction at a time. `CONDITIONAL_UPDATE` only saves one
lock-acquiring round trip, which does not change that. Confirming it would need PostgreSQL wait-event data
(`pg_stat_activity`) during a run.

**Short runs mislead.** An early 15-second smoke test showed `CONDITIONAL_UPDATE` 20% faster at low contention and 47%
faster at high contention; that gap vanished in proper runs. This is why only repeated 60-second runs are used for
conclusions.

## History: v0.4, before reversals and the outbox (single 60 s run per row)

| Strategy | Wallets | Throughput | p99 |
|---|---|---|---|
| PESSIMISTIC | 1000 | 2,224 /s | 23.9 ms |
| CONDITIONAL_UPDATE | 1000 | 2,066 /s | 26.5 ms |
| PESSIMISTIC | 2 | 340 /s | 401.0 ms |
| CONDITIONAL_UPDATE | 2 | 351 /s | 439.5 ms |

Since then, every transfer also writes an outbox event row in the same transaction, and a background dispatcher
delivers and marks those events. Throughput is lower: about 26% at low contention (2,224 to 1,641 for `PESSIMISTIC`)
and about 50% under high contention (340 to 172). The ablation below separates the causes.

## Ablation: what does the outbox dispatcher cost?

To find out why the high-contention throughput halved, the same benchmark was repeated with the dispatcher switched off
(`LEDGERX_OUTBOX_ENABLED=false`): events are still written in every transfer's transaction, but nothing delivers them.
`PESSIMISTIC` only, 3 runs of 60 s per row, fresh database, same method as above.

| Wallets | v0.4: no outbox (1 run) | Outbox written, dispatcher off (3 runs) | Outbox written, dispatcher on (3 runs) |
|---|---|---|---|
| 1000, throughput | 2,224 /s | 1,569 /s (1,425-1,600) | 1,641 /s (1,359-1,694) |
| 1000, p99 | 23.9 ms | 40.4 ms (39.9-43.2) | 32.8 ms (32.6-43.8) |
| 2, throughput | 340 /s | 249 /s (237-252) | 172 /s (169-173) |
| 2, p99 | 401.0 ms | 442.6 ms (430.4-469.2) | 634.6 ms (623.6-659.0) |

What the data supports:

- **Under high contention the dispatcher costs about 31% of throughput** (249 to 172 transfers/s) and raises p99 by
  about 43% (443 to 635 ms). The ranges do not overlap, so this is a real effect, not noise.
- **At low contention the dispatcher is not measurable.** The throughput ranges (1,425-1,600 off vs 1,359-1,694 on)
  overlap almost completely.
- **Most of the remaining gap to v0.4 is not the dispatcher.** With the dispatcher off, throughput is still about 29%
  below v0.4 at low contention (2,224 to 1,569) and about 27% below at high contention (340 to 249). That is the cost of
  the extra work in the transfer transaction (the outbox insert and its JSON payload, the added `reverses_transfer_id`
  column and index), plus any difference in conditions between the two measurements.

What it does not show:

- **Why** the dispatcher hurts under contention. A plausible mechanism is that its updates and write-ahead-log traffic
  compete with transactions that are holding the two hot row locks, which lengthens every lock hold. That is a
  hypothesis; confirming it would need PostgreSQL wait-event data during a run.
- **How much of the v0.4 gap is the code and how much is drift.** The v0.4 figures are a single run taken on a
  different day, so that comparison is weaker than the dispatcher on/off comparison, where both sides ran back to back
  on the same fresh stack. Re-running the `v0.4.0` tag under today's conditions would settle it.
- Anything about `CONDITIONAL_UPDATE`: the ablation ran `PESSIMISTIC` only.

Limitations of the ablation: the configurations ran one after another rather than interleaved, and with the dispatcher
off about 363,000 undelivered events accumulated in the outbox table, which a real deployment would not do.

Practical reading: the dispatcher's cost shows up only when many transfers contend for the same rows. For workloads
spread over many wallets it is negligible. Ideas for reducing it (not yet tried): marking a whole batch delivered with a
single statement, and a longer poll interval.

## Limitations

- Three runs per configuration give a range, not a confidence interval. The v0.4 history table is a single run per row.
- Application, database and load generator share one machine and compete for CPU and disk.
- Default PostgreSQL settings (`synchronous_commit` on); results would differ with tuned durability settings.
- Transfers are uniformly random across wallets. Real traffic is often skewed towards a few hot wallets, which is
  closer to the 2-wallet case than the 1000-wallet case.
