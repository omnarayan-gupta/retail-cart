#!/usr/bin/env bash
# End-to-end smoke test against the running stack, using real Target product data.
#
#   docker compose up -d --build
#   scripts/smoke.sh            # customer flow, retries and error cases
#   scripts/smoke.sh --outage   # ...then stops the product services to show graceful degradation
#
# Needs only bash and curl. Exits non-zero if any check fails.
set -uo pipefail

CART_URL="${CART_URL:-http://localhost:8081}"
PRODUCT_URL="${PRODUCT_URL:-http://localhost:8080}"
API="$CART_URL/api/v1/carts"

TEE=54276335       # Hanes tee: SALE price, limit 20
CLEANER=15744221   # Mrs. Meyer's: REGULAR price, limit 10
SCARCE=76180307    # Hape kitchen set: only 5 in stock, limit 2
UNKNOWN=99999999   # not in the catalog

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASSED=0
FAILED=0

# request METHOD URL [JSON_BODY] [IDEMPOTENCY_KEY] -> sets STATUS, BODY; headers in $TMP/headers
request() {
  local method=$1 url=$2 body=${3:-} key=${4:-}
  local args=(-s -X "$method" -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}')
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  [[ -n $key ]] && args+=(-H "Idempotency-Key: $key")
  STATUS=$(curl "${args[@]}" "$url")
  BODY=$(cat "$TMP/body")
}

# field NAME -> first value of "NAME" in BODY (string, number or boolean)
field() {
  grep -o "\"$1\":\"\{0,1\}[^\",}]*" <<<"$BODY" | head -1 | sed "s/^\"$1\":\"\{0,1\}//"
}
header() { grep -i "^$1:" "$TMP/headers" | head -1 | cut -d' ' -f2- | tr -d '\r'; }

check() { # DESCRIPTION EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then
    PASSED=$((PASSED + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"
  else
    FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s (expected %s, got %s)\n' "$1" "$2" "$3"
    printf '       %s\n' "$BODY"
  fi
}

new_key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "smoke-$RANDOM-$RANDOM-$(date +%s)"; }

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ------------------------------------------------------------------------------------------------
section "Services are up"
request GET "$PRODUCT_URL/product/v1/health"; check "Target product services healthy" 200 "$STATUS"
request GET "$CART_URL/actuator/health";      check "cart service healthy" UP "$(field status)"

section "Customer flow"
KEY=$(new_key)
request POST "$API/items" "{\"itemId\":\"$TEE\",\"quantity\":2}" "$KEY"
check "first add creates a cart (201)" 201 "$STATUS"
CART=$(field cartId)
check "Location header points at the new cart" "$API/$CART" "$(header Location)"

request POST "$API/items" "{\"itemId\":\"$TEE\",\"quantity\":2}" "$KEY"
check "retry with the same Idempotency-Key replays (201)" 201 "$STATUS"
check "...the same cart, not a second one" "$CART" "$(field cartId)"
check "...and says it was replayed" true "$(header Idempotent-Replayed)"

request POST "$API/$CART/items" "{\"itemId\":\"$TEE\",\"quantity\":1}"
check "adding the same item merges quantities (2+1)" 3 "$(field quantity)"

request POST "$API/$CART/items" "{\"itemId\":\"$CLEANER\",\"quantity\":1}"
check "add a second item (200)" 200 "$STATUS"
check "itemCount counts units across lines" 4 "$(field itemCount)"

request PATCH "$API/$CART/items/$TEE" '{"quantity":5}'
check "PATCH sets an absolute quantity" 5 "$(field quantity)"

request GET "$API/$CART"
check "view the cart (200)" 200 "$STATUS"
check "every line priced and available" false "$(field totalIncomplete)"
check "sale price is used for a SALE item" SALE "$(field priceType)"
printf '       subtotal %s %s, priced live at %s\n' "$(field subtotal)" "$(field currency)" "$(field pricedAt)"

request DELETE "$API/$CART/items/$CLEANER"
check "remove an item (204)" 204 "$STATUS"
request DELETE "$API/$CART/items/$CLEANER"
check "removing it again: ITEM_NOT_IN_CART (404)" 404 "$STATUS"

section "Business rules and errors"
request PATCH "$API/$CART/items/$TEE" '{"quantity":25}'
check "over the purchase limit (409)" QUANTITY_LIMIT_EXCEEDED "$(field code)"
check "...tells the client the maximum" 20 "$(field maxAllowed)"

request POST "$API/$CART/items" "{\"itemId\":\"$SCARCE\",\"quantity\":3}"
check "scarce item capped at its limit of 2 (409)" 2 "$(field maxAllowed)"

request POST "$API/$CART/items" "{\"itemId\":\"$UNKNOWN\",\"quantity\":1}"
check "unknown item: ITEM_NOT_FOUND (422)" 422 "$STATUS"

request POST "$API/$CART/items" '{"itemId":"abc","quantity":0}'
check "invalid input: VALIDATION_FAILED (400)" VALIDATION_FAILED "$(field code)"

request POST "$API/$CART/items" "{\"itemId\":\"$TEE\",\"quantity\":9}" "$KEY"
check "Idempotency-Key reused for another request (422)" IDEMPOTENCY_KEY_REUSED "$(field code)"

request GET "$API/00000000-0000-0000-0000-000000000000"
check "unknown cart: CART_NOT_FOUND (404)" 404 "$STATUS"

request GET "$API/not-a-uuid"
check "malformed cart id (400)" 400 "$STATUS"

# ------------------------------------------------------------------------------------------------
if [[ "${1:-}" == "--outage" ]]; then
  section "Outage: Target product services stopped"
  cd "$(dirname "$0")/.." || exit 1
  if ! docker compose stop product >/dev/null; then
    echo "Could not stop the product container (is docker on PATH, and the stack from docker-compose.yml running?)" >&2
    exit 1
  fi
  trap 'docker compose start product >/dev/null 2>&1; rm -rf "$TMP"' EXIT

  request GET "$API/$CART"
  check "cart view still works (200)" 200 "$STATUS"
  check "...flagged as incomplete" true "$(field totalIncomplete)"
  check "...line shows it couldn't check availability" AVAILABILITY_UNKNOWN "$(field status)"

  request POST "$API/$CART/items" "{\"itemId\":\"$CLEANER\",\"quantity\":1}"
  check "add fails closed: DEPENDENCY_UNAVAILABLE (503)" 503 "$STATUS"
  check "...with Retry-After" 5 "$(header Retry-After)"

  request GET "$CART_URL/actuator/health"
  check "cart service stays healthy: no restart cascade" UP "$(field status)"

  section "Recovery"
  docker compose start product >/dev/null || exit 1
  for _ in $(seq 1 30); do
    request GET "$API/$CART"
    [[ "$(field totalIncomplete)" == false ]] && break
    sleep 1 # circuit breakers stay open for 10s, then probe
  done
  check "prices and availability back once breakers close" false "$(field totalIncomplete)"
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[[ $FAILED -eq 0 ]]
