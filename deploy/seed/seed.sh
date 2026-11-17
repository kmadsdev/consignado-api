#!/bin/sh
# Seed the consignado API with SYNTHETIC demo data, through the public workflow endpoints only.
#
#   sh seed.sh http://consignado-api.consignado.svc
#
# POSIX sh + curl (runs in the image curlimages/curl). No jq, no $RANDOM: every value comes
# from a fixed list or a fixed formula, so two runs on two empty databases give the same data.
# See README.md in this folder for what it creates and why.
set -eu

BASE="${1:-}"
if [ -z "$BASE" ]; then
  echo "usage: seed.sh <base-url>   (example: http://consignado-api.consignado.svc)" >&2
  exit 2
fi
BASE="${BASE%/}"
API="$BASE/api/v1"

CUSTOMERS=50          # one first simulation each
CONTRACTS=30          # from the first simulation of customers 1..30
SECOND_SIMULATIONS=10 # customers 1..10 quote again after their contract
REFUSED_ATTEMPTS=3    # customers 31..33 ask for too much: the API answers 422 margin-exceeded

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body"
STATUS=""

fail() {
  echo "seed: FAILED - $1" >&2
  [ -s "$BODY" ] && { echo "seed: last response body:" >&2; head -c 600 "$BODY" >&2; echo >&2; }
  exit 1
}

# call METHOD PATH [JSON] [EXTRA_HEADER]
# Sets STATUS and writes the response to $BODY. Retries only when no connection was made
# (curl exit 6 or 7), so a request is never sent twice.
call() {
  method="$1"; path="$2"; data="${3:-}"; header="${4:-}"
  try=1
  while :; do
    : > "$BODY"
    set -- -sS -o "$BODY" -w '%{http_code}' --connect-timeout 10 --max-time 60 \
      -X "$method" -H 'Accept: application/json'
    [ -n "$data" ] && set -- "$@" -H 'Content-Type: application/json' --data-binary "$data"
    [ -n "$header" ] && set -- "$@" -H "$header"
    rc=0
    STATUS="$(curl "$@" "$API$path")" || rc=$?
    [ "$rc" -eq 0 ] && return 0
    if { [ "$rc" -eq 6 ] || [ "$rc" -eq 7 ]; } && [ "$try" -lt 5 ]; then
      try=$((try + 1)); sleep 3; continue
    fi
    fail "$method $path: curl exit code $rc"
  done
}

# expect CODE WHAT: stop when the last call did not return CODE.
expect() {
  [ "$STATUS" = "$1" ] || fail "$2: expected HTTP $1, got $STATUS"
}

# First "id" of the last response (the id of the resource; nested ids have other key names).
last_id() {
  id="$(grep -o '"id":"[^"]*"' "$BODY" | head -n 1 | cut -d'"' -f4)"
  [ -n "$id" ] || fail "no id in the response"
  echo "$id"
}

# totalElements of a paged listing.
total_of() {
  call GET "$1"
  expect 200 "GET $1"
  grep -o '"totalElements":[0-9]*' "$BODY" | head -n 1 | cut -d: -f2
}

# nth N WORD...: the Nth word (0-based), wrapping around.
nth() {
  n="$1"; shift
  shift $((n % $#))
  echo "$1"
}

# ---------------------------------------------------------------- fixed data

# code|name|maxMarginPercent|maxTermMonths|monthlyInterestRate
AGREEMENTS='DEMO-INSS|Convênio INSS (demo)|35.00|84|0.0155
DEMO-ESTADO|Convênio Servidores do Estado (demo)|35.00|96|0.0175
DEMO-PREFEITURA|Convênio Prefeitura Municipal (demo)|30.00|72|0.0195
DEMO-EMPRESA|Convênio Empresa Privada (demo)|30.00|60|0.0210'
MARKER_CODE='DEMO-INSS'

FIRST_NAMES='Ana Bruno Carla Daniel Eduarda Fábio Gabriela Henrique Isabela Jonas Karina Leandro Mariana Nelson Olívia Paulo Renata Samuel Tatiane Ulisses Vanessa Wagner Yasmin Alberto Beatriz'
MIDDLE_NAMES='Almeida Barbosa Cardoso Duarte Esteves Ferreira Gomes Lopes Martins Nunes'
LAST_NAMES='Oliveira Pereira Queiroz Ribeiro Souza Teixeira Vieira'

TERMS='24 36 48 60 72 84 96'

# Target state of contract 1..30. CANCELLED_x = cancelled from state x.
TARGETS='ACTIVE ACTIVE APPROVED PROPOSED SETTLED REJECTED ACTIVE CANCELLED_ACTIVE APPROVED PROPOSED
ACTIVE SETTLED REJECTED ACTIVE APPROVED CANCELLED_PROPOSED ACTIVE PROPOSED SETTLED ACTIVE
REJECTED APPROVED CANCELLED_APPROVED ACTIVE PROPOSED SETTLED APPROVED REJECTED CANCELLED_ACTIVE PROPOSED'

REJECT_REASON='Payroll data not confirmed by the employer (demo)'
CANCEL_REASON='Cancelled at the request of the customer (demo)'

# Synthetic CPF number i: a 9-digit base from a fixed arithmetic sequence plus the two
# check digits of the official rule. Not copied from any source, not a real person's data.
cpf_of() {
  base=$((111222333 + $1 * 13579246))
  n=$base; k=9; s1=0; s2=0
  while [ "$k" -ge 1 ]; do
    d=$((n % 10)); n=$((n / 10))
    s1=$((s1 + d * (11 - k))); s2=$((s2 + d * (12 - k)))
    k=$((k - 1))
  done
  d1=$((s1 % 11)); if [ "$d1" -lt 2 ]; then d1=0; else d1=$((11 - d1)); fi
  s2=$((s2 + d1 * 2))
  d2=$((s2 % 11)); if [ "$d2" -lt 2 ]; then d2=0; else d2=$((11 - d2)); fi
  printf '%09d%d%d' "$base" "$d1" "$d2"
}

# Whole reais of the net monthly salary of customer i: 1800 .. 18000.
salary_of() {
  echo $((1800 + ($1 * 3571) % 16201))
}

name_of() {
  # shellcheck disable=SC2086
  echo "$(nth "$1" $FIRST_NAMES) $(nth "$1" $MIDDLE_NAMES) $(nth "$1" $LAST_NAMES)"
}

# Term for quote q of a customer whose agreement allows at most $2 months.
term_of() {
  # shellcheck disable=SC2086
  t="$(nth $(($1 * 5)) $TERMS)"
  [ "$t" -gt "$2" ] && t="$2"
  echo "$t"
}

# ---------------------------------------------------------------- guard

echo "seed: target $BASE"
call GET /agreements
expect 200 "GET /agreements"
if grep -q "\"code\":\"$MARKER_CODE\"" "$BODY"; then
  echo "seed: already seeded (agreement $MARKER_CODE exists). Nothing was changed."
  exit 0
fi

# ---------------------------------------------------------------- agreements

a=0
echo "$AGREEMENTS" > "$TMP/agreements"
while IFS='|' read -r code name margin term rate; do
  call POST /agreements \
    "{\"code\":\"$code\",\"name\":\"$name\",\"maxMarginPercent\":$margin,\"maxTermMonths\":$term,\"monthlyInterestRate\":$rate}"
  expect 201 "create agreement $code"
  eval "AGREEMENT_ID_$a=$(last_id)"
  eval "AGREEMENT_TERM_$a=$term"
  a=$((a + 1))
done < "$TMP/agreements"
AGREEMENT_COUNT=$a
echo "seed: agreements created: $AGREEMENT_COUNT"

# ---------------------------------------------------------------- customers

i=1
while [ "$i" -le "$CUSTOMERS" ]; do
  eval "agreement_id=\$AGREEMENT_ID_$((i % AGREEMENT_COUNT))"
  salary="$(salary_of "$i")"
  cents="$(printf '%02d' $((i * 17 % 100)))"
  call POST /customers \
    "{\"cpf\":\"$(cpf_of "$i")\",\"fullName\":\"$(name_of "$i")\",\"agreementId\":\"$agreement_id\",\"netMonthlySalary\":$salary.$cents}"
  expect 201 "create customer $i"
  eval "CUSTOMER_ID_$i=$(last_id)"
  i=$((i + 1))
done
echo "seed: customers created: $CUSTOMERS"

# ---------------------------------------------------------------- first simulations

# The amount is 2, 3 or 4 net salaries, rounded down to R$ 100. With the highest rate (2.1%)
# and the shortest term (24 months) the installment of 4 salaries is about 21.4% of the
# salary, below the lowest margin (30%). So every one of these quotes fits.
i=1
while [ "$i" -le "$CUSTOMERS" ]; do
  eval "customer_id=\$CUSTOMER_ID_$i"
  eval "max_term=\$AGREEMENT_TERM_$((i % AGREEMENT_COUNT))"
  amount=$(( $(salary_of "$i") * (i % 3 + 2) / 100 * 100 ))
  call POST /simulations \
    "{\"customerId\":\"$customer_id\",\"amount\":$amount.00,\"termMonths\":$(term_of "$i" "$max_term")}"
  expect 201 "simulate for customer $i"
  eval "SIMULATION_ID_$i=$(last_id)"
  i=$((i + 1))
done
echo "seed: first simulations created: $CUSTOMERS"

# ---------------------------------------------------------------- contracts

# step CONTRACT_ID ACTION [REASON]: one move of the state machine.
step() {
  if [ -n "${3:-}" ]; then
    call POST "/contracts/$1/$2" "{\"reason\":\"$3\"}"
  else
    call POST "/contracts/$1/$2"
  fi
  expect 200 "$2 contract $1"
}

j=1
# shellcheck disable=SC2086
for target in $TARGETS; do
  eval "simulation_id=\$SIMULATION_ID_$j"
  key="$(printf 'seed-contract-%03d' "$j")"
  call POST /contracts "{\"simulationId\":\"$simulation_id\"}" "Idempotency-Key: $key"
  expect 201 "propose contract $j"
  c="$(last_id)"
  case "$target" in
    PROPOSED) ;;
    APPROVED) step "$c" approve ;;
    REJECTED) step "$c" reject "$REJECT_REASON" ;;
    ACTIVE) step "$c" approve; step "$c" activate ;;
    SETTLED) step "$c" approve; step "$c" activate; step "$c" settle ;;
    CANCELLED_PROPOSED) step "$c" cancel "$CANCEL_REASON" ;;
    CANCELLED_APPROVED) step "$c" approve; step "$c" cancel "$CANCEL_REASON" ;;
    CANCELLED_ACTIVE) step "$c" approve; step "$c" activate; step "$c" cancel "$CANCEL_REASON" ;;
    *) fail "unknown target state $target" ;;
  esac
  j=$((j + 1))
done
[ "$j" -eq $((CONTRACTS + 1)) ] || fail "the TARGETS list must have $CONTRACTS entries"
echo "seed: contracts created: $CONTRACTS"

# ---------------------------------------------------------------- second simulations

# One more salary over at least 36 months is about 4% of the salary. Added to the 21.4%
# that an approved or active first contract can take, it is still below 30%.
i=1
while [ "$i" -le "$SECOND_SIMULATIONS" ]; do
  eval "customer_id=\$CUSTOMER_ID_$i"
  eval "max_term=\$AGREEMENT_TERM_$((i % AGREEMENT_COUNT))"
  amount=$(( $(salary_of "$i") / 100 * 100 ))
  call POST /simulations \
    "{\"customerId\":\"$customer_id\",\"amount\":$amount.00,\"termMonths\":$(term_of $((i + 1)) "$max_term")}"
  expect 201 "second simulation for customer $i"
  i=$((i + 1))
done
echo "seed: second simulations created: $SECOND_SIMULATIONS"

# ---------------------------------------------------------------- refused attempts

# 15 net salaries in 24 months is an installment of at least 74% of the salary. The API
# must refuse it with 422 margin-exceeded and store nothing.
refused=0
i=31
while [ "$i" -lt $((31 + REFUSED_ATTEMPTS)) ]; do
  eval "customer_id=\$CUSTOMER_ID_$i"
  call POST /simulations \
    "{\"customerId\":\"$customer_id\",\"amount\":$(( $(salary_of "$i") * 15 )).00,\"termMonths\":24}"
  expect 422 "over-margin simulation for customer $i"
  grep -q '"code":"margin-exceeded"' "$BODY" || fail "over-margin simulation for customer $i: 422 without code margin-exceeded"
  refused=$((refused + 1))
  i=$((i + 1))
done
echo "seed: over-margin attempts refused by the API (422, expected): $refused"

# ---------------------------------------------------------------- summary (read back from the API)

call GET /agreements
expect 200 "GET /agreements"
agreements_now="$(grep -o '"code":"' "$BODY" | wc -l | tr -d ' ')"
echo "seed: SUMMARY"
echo "  agreements:  $agreements_now"
echo "  customers:   $(total_of '/customers?size=1')"
echo "  simulations: $(total_of '/simulations?size=1')"
echo "  contracts:   $(total_of '/contracts?size=1')"
for s in PROPOSED APPROVED REJECTED ACTIVE SETTLED CANCELLED; do
  echo "    $s: $(total_of "/contracts?status=$s&size=1")"
done
echo "seed: done"
