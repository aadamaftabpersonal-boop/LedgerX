# LedgerX

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

## API (so far)

| Method | Path | Body |
|---|---|---|
| POST | `/wallets` | `{"owner":"alice","currency":"INR"}` |
| GET | `/wallets/{id}` | |
| POST | `/wallets/{id}/deposit` | `{"amount":5000}` (minor units) |
| POST | `/transfers` | `{"fromWalletId":"...","toWalletId":"...","amount":1200}` |

Supported currencies: INR, USD, EUR. Amounts are integer minor units (paise, cents).

## Design notes

- Money is `BIGINT` minor units, never floating point.
- Each transfer writes two ledger entries (debit and credit) that sum to zero; the ledger table rejects UPDATE and DELETE.
- Deposits are transfers from a per-currency EXTERNAL wallet, so the sum of all wallet balances is always zero.
- Transfers lock both wallets with `SELECT ... FOR UPDATE` in ascending id order to avoid deadlocks.

## Roadmap

- [x] v0.1 Schema, wallets, ledger, basic transfers, Testcontainers
- [ ] v0.2 Idempotency keys, locking strategies
- [ ] v0.3 Concurrency and failure tests, auth, CI badge
- [ ] v0.4 Load tests, metrics, reconciliation, architecture doc
