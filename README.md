# LedgerX

![CI](https://github.com/aadamaftabpersonal-boop/LegderX/actions/workflows/ci.yml/badge.svg)

A transaction-safe wallet and payments engine: consistent balances under concurrent requests, no duplicate transfers, an append-only double-entry ledger.

Stack: Java 21, Spring Boot 3, PostgreSQL, Flyway, Testcontainers, Docker Compose.

## Run

Everything in Docker (app + PostgreSQL), one command:

```bash
docker compose up --build
```

Or run the app from your IDE / Maven against just the database:

```bash
docker compose up -d postgres
LEDGERX_ADMIN_KEY=dev-admin-key ./mvnw spring-boot:run
```

The containerised app is published on http://localhost:8081 (set `APP_PORT` to change it; running with Maven uses 8080).

Optional Prometheus and Grafana (http://localhost:3000, dashboard "LedgerX"):

```bash
docker compose --profile monitoring up -d
```

## Test

Needs Docker running (Testcontainers starts a real PostgreSQL).

```bash
./mvnw verify
```

## Authentication

Every endpoint except `/actuator/*` and `/admin/*` needs an `X-API-Key` header. Create a client (the key is shown once):

```bash
LEDGERX_ADMIN_KEY=change-me mvn spring-boot:run
curl -X POST localhost:8080/admin/clients -H "X-Admin-Key: change-me" -H "Content-Type: application/json" -d '{"name":"my-app"}'
```

A client can only read and deposit into its own wallets and only send money from its own wallets. Other clients' wallets look nonexistent (404). Only the SHA-256 hash of an API key is stored. Idempotency keys are scoped per client.

## API

| Method | Path | Body |
|---|---|---|
| POST | `/wallets` | `{"owner":"alice","currency":"INR"}` |
| GET | `/wallets/{id}` | |
| POST | `/wallets/{id}/deposit` | `{"amount":5000}` (minor units) |
| POST | `/transfers` | `{"fromWalletId":"...","toWalletId":"...","amount":1200}` plus required `Idempotency-Key` header |

Supported currencies: INR, USD, EUR. Amounts are integer minor units (paise, cents).

## Design notes

- Money is `BIGINT` minor units, never floating point.
- Each transfer writes two ledger entries (debit and credit) that sum to zero; the ledger table rejects UPDATE and DELETE.
- Deposits are transfers from a per-currency EXTERNAL wallet, so the sum of all wallet balances is always zero.
- Transfers lock both wallets with `SELECT ... FOR UPDATE` in ascending id order to avoid deadlocks (`PESSIMISTIC`, default).
- `CONDITIONAL_UPDATE` is a lock-free alternative: the debit is one `UPDATE ... WHERE balance >= :amount`, and the two updates run in id order. Select it with `ledgerx.transfer.lock-strategy`.
- Idempotency: the `Idempotency-Key` row is inserted in the same transaction as the transfer (`ON CONFLICT DO NOTHING`). A concurrent duplicate blocks on the unique index, then replays the stored result (HTTP 200, `Idempotent-Replayed: true`). Reusing a key with a different body returns 409. A failed transfer rolls its key back, so retrying after a failure executes again.

## Operations

- `X-Request-Id` is returned on every response and appears in every log line for that request. Docker runs log JSON (ECS format).
- Metrics at `/actuator/prometheus`: `ledgerx_transfer_seconds` (count, sum, buckets) tagged by `outcome` and `strategy`, plus Hikari pool and JVM metrics.
- Reconciliation audits balances against the ledger every 5 minutes and on demand: `GET /admin/reconciliation` (with `X-Admin-Key`). Result is also the gauge `ledgerx_reconciliation_discrepancies`.
- Load test results: see [docs/BENCHMARKS.md](docs/BENCHMARKS.md) (about 2,200 transfers/s at p99 24 ms with low contention; about 340/s at p99 400 ms when every request hits the same two wallets; 0 failures, ledger reconciled). Design and trade-offs: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Roadmap

- [x] v0.1 Schema, wallets, ledger, basic transfers, Testcontainers
- [x] v0.2 Idempotency keys, locking strategies, concurrency tests
- [x] v0.3 Failure injection tests, API-key auth, CI badge
- [x] v0.4 Metrics, reconciliation, Docker, load tests, architecture doc
- [x] v1.0 Benchmarks recorded in docs/BENCHMARKS.md
- [ ] Demo GIF
