# LedgerX

![CI](https://github.com/aadamaftabpersonal-boop/LegderX/actions/workflows/ci.yml/badge.svg)

A transaction-safe wallet and payments engine: consistent balances under concurrent requests, no duplicate transfers, an append-only double-entry ledger.

Stack: Java 21, Spring Boot 3, PostgreSQL, Flyway, Testcontainers, Docker Compose.

## Run

```bash
docker compose up -d
mvn spring-boot:run
```

## Test

Needs Docker running (Testcontainers starts a real PostgreSQL).

```bash
mvn verify
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

## Roadmap

- [x] v0.1 Schema, wallets, ledger, basic transfers, Testcontainers
- [x] v0.2 Idempotency keys, locking strategies, concurrency tests
- [x] v0.3 Failure injection tests, API-key auth, CI badge
- [ ] v0.4 Load tests, metrics, reconciliation, architecture doc
