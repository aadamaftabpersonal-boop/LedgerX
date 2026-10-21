#!/usr/bin/env bash
# End-to-end check against a running stack (docker compose up -d --build --wait app).
# Exercises the public API the way a client would, and fails loudly on any unexpected status.
set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
ADMIN_KEY="${ADMIN_KEY:-dev-admin-key}"
JSON='Content-Type: application/json'
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
BODY=""

fail() { echo "FAIL: $*" >&2; exit 1; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# call EXPECTED_STATUS LABEL METHOD PATH [curl args...]   (sets BODY to the response body)
call() {
  local expected="$1" label="$2" method="$3" path="$4"
  shift 4
  local code
  code="$(curl -s -o "$TMP" -w '%{http_code}' -X "$method" "$BASE$path" "$@")"
  BODY="$(cat "$TMP")"
  [ "$code" = "$expected" ] || fail "$label: expected HTTP $expected, got $code ($BODY)"
  echo "ok   $label ($code)"
}

# A here-string avoids SIGPIPE: with pipefail, "echo | grep -q" fails on large bodies because grep exits early.
contains() { grep -q "$1" <<<"$BODY" || fail "$2 (response: ${BODY:0:300})"; }

echo "== health"
for _ in $(seq 1 30); do curl -sf "$BASE/actuator/health" >/dev/null && break; sleep 2; done
call 200 "health" GET /actuator/health

echo "== client and wallets"
call 201 "create client" POST /admin/clients -H "X-Admin-Key: $ADMIN_KEY" -H "$JSON" -d '{"name":"smoke"}'
API_KEY="$(echo "$BODY" | field apiKey)"
[ -n "$API_KEY" ] || fail "no apiKey in response"
AUTH=(-H "X-API-Key: $API_KEY")

call 201 "create wallet alice" POST /wallets "${AUTH[@]}" -H "$JSON" -d '{"owner":"alice","currency":"INR"}'
ALICE="$(echo "$BODY" | field id)"
call 201 "create wallet bob" POST /wallets "${AUTH[@]}" -H "$JSON" -d '{"owner":"bob","currency":"INR"}'
BOB="$(echo "$BODY" | field id)"

echo "== money"
call 201 "deposit" POST "/wallets/$ALICE/deposit" "${AUTH[@]}" -H "$JSON" -d '{"amount":5000}'
KEY="smoke-$(date +%s)-$$"
TRANSFER="{\"fromWalletId\":\"$ALICE\",\"toWalletId\":\"$BOB\",\"amount\":1200}"
call 201 "transfer" POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" -H "$JSON" -d "$TRANSFER"
TRANSFER_ID="$(echo "$BODY" | field id)"
call 200 "retry replays the original" POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" -H "$JSON" -d "$TRANSFER"
call 409 "key reuse with a different body" POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY" -H "$JSON" -d "${TRANSFER/1200/1201}"
call 422 "insufficient funds" POST /transfers "${AUTH[@]}" -H "Idempotency-Key: $KEY-big" -H "$JSON" -d "${TRANSFER/1200/999999}"
call 401 "missing api key" GET "/wallets/$ALICE"

echo "== balances"
call 200 "read alice" GET "/wallets/$ALICE" "${AUTH[@]}"
contains '"balance":3800' "alice should have 3800"
call 200 "read bob" GET "/wallets/$BOB" "${AUTH[@]}"
contains '"balance":1200' "bob should have 1200"

echo "== refund (this client owns both wallets, so it is also the recipient)"
call 201 "reverse the transfer" POST "/transfers/$TRANSFER_ID/reverse" "${AUTH[@]}" -H "Idempotency-Key: $KEY-refund"
call 409 "second reversal is rejected" POST "/transfers/$TRANSFER_ID/reverse" "${AUTH[@]}" -H "Idempotency-Key: $KEY-refund-2"
call 200 "read alice after refund" GET "/wallets/$ALICE" "${AUTH[@]}"
contains '"balance":5000' "alice should have her 5000 back"

echo "== key rotation"
call 201 "rotate key" POST /keys/rotate "${AUTH[@]}" -H "$JSON" -d '{"gracePeriodSeconds":60}'
NEW_KEY="$(echo "$BODY" | field apiKey)"
call 200 "new key works" GET /keys -H "X-API-Key: $NEW_KEY"
call 200 "old key still works during the grace period" GET /keys "${AUTH[@]}"

echo "== operations"
call 200 "reconciliation" GET /admin/reconciliation -H "X-Admin-Key: $ADMIN_KEY"
contains '"consistent":true' "ledger is not consistent"
call 200 "metrics" GET /actuator/prometheus
contains 'ledgerx_transfer_seconds_count' "transfer metric missing"
contains 'ledgerx_outbox_pending' "outbox metric missing"
call 200 "openapi spec" GET /v3/api-docs

echo
echo "Smoke test passed."
