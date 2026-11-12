#!/usr/bin/env bash
# E2E duman testi yardımcıları: adım çerçevesi (PASS/FAIL, süre, özet), HTTP/gRPC sarmalayıcıları, JWT okuma.
# Bu dosya doğrudan çalıştırılmaz; smoke.sh içe aktarır.

GATEWAY="${GATEWAY_URL:-http://api-gateway:8080}"
KEYCLOAK="${KEYCLOAK_URL:-http://keycloak:8180}"
PSP_SIM="${PSP_SIMULATOR_URL:-http://psp-simulator:8085}"
CONNECT="${CONNECT_URL:-http://connect:8083}"
PRICING_GRPC="${PRICING_GRPC:-pricing-service:9093}"
LOCATION_GRPC="${LOCATION_GRPC:-location-service:9090}"
LEDGER_GRPC="${LEDGER_GRPC:-ledger-service:9091}"
SMS_SINK="${SMS_SINK_FILE:-/sms/sink.jsonl}"
TOKEN_URL="$KEYCLOAK/realms/routemesh/protocol/openid-connect/token"

WORK="$(mktemp -d)"
BODY_FILE="$WORK/body"
STATUS=""
BODY=""
STEP_NOTES=()
SUMMARY=()
FAILED=0
ABORT=0
STEP_NO=0
SUITE_START=$(date +%s%3N)

cleanup() {
  [[ -n "${SSE_PID:-}" ]] && kill "$SSE_PID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------------------
# Adım çerçevesi
# ---------------------------------------------------------------------------------------------------------

note() { STEP_NOTES+=("$*"); }

# Başarısızlıkta adımı bitirir: mesajı not eder ve 1 döner (çağıran `|| return 1` ile zincirler)
fail() { note "HATA: $*"; return 1; }

# run_step "Başlık" fonksiyon: fonksiyon geçerli kabukta çalışır (global değişkenleri paylaşır)
run_step() {
  local title="$1" fn="$2" start end ms result
  STEP_NO=$((STEP_NO + 1))
  local label
  label=$(printf '%02d %s' "$STEP_NO" "$title")
  if (( ABORT )); then
    printf '[SKIP] %s\n' "$label"
    SUMMARY+=("SKIP|$label|-")
    return 0
  fi
  STEP_NOTES=()
  start=$(date +%s%3N)
  if "$fn"; then result=PASS; else result=FAIL; FAILED=$((FAILED + 1)); ABORT=1; fi
  end=$(date +%s%3N)
  ms=$((end - start))
  printf '[%s] %s (%d ms)\n' "$result" "$label" "$ms"
  local line
  for line in "${STEP_NOTES[@]}"; do printf '         %s\n' "$line"; done
  SUMMARY+=("$result|$label|$ms")
}

print_summary() {
  local total=$(( $(date +%s%3N) - SUITE_START )) pass=0 skip=0 entry
  echo
  echo "================ ÖZET ================"
  for entry in "${SUMMARY[@]}"; do
    IFS='|' read -r result label ms <<<"$entry"
    printf '%-5s %-62s %8s\n' "$result" "$label" "${ms}$([[ $ms == - ]] || echo ' ms')"
    [[ $result == PASS ]] && pass=$((pass + 1))
    [[ $result == SKIP ]] && skip=$((skip + 1))
  done
  echo "--------------------------------------"
  printf 'Toplam süre: %d ms | PASS: %d | FAIL: %d | SKIP: %d
' "$total" "$pass" "$FAILED" "$skip"
  if (( FAILED == 0 )); then echo "SONUÇ: BAŞARILI"; else echo "SONUÇ: BAŞARISIZ"; fi
}

# ---------------------------------------------------------------------------------------------------------
# Yardımcılar
# ---------------------------------------------------------------------------------------------------------

# retry <saniye-zaman-aşımı> <aralık-saniye> komut...  (komut 0 dönene kadar)
retry() {
  local timeout="$1" interval="$2" deadline
  shift 2
  deadline=$(( $(date +%s) + timeout ))
  until "$@"; do
    (( $(date +%s) >= deadline )) && return 1
    sleep "$interval"
  done
}

# http METHOD URL [curl argümanları...]  -> STATUS ve BODY
http() {
  local method="$1" url="$2"
  shift 2
  STATUS=$(curl -sS -m 30 -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$url" "$@" 2>"$WORK/curl.err") || STATUS=000
  BODY=$(cat "$BODY_FILE" 2>/dev/null)
  : >"$BODY_FILE"
}

auth() { printf 'Authorization: Bearer %s' "$1"; }

user_token() { # kullanıcı parola
  curl -sS -m 20 -X POST "$TOKEN_URL" -d grant_type=password -d client_id=routemesh-web \
    -d "username=$1" -d "password=$2" | jq -r '.access_token // empty'
}

client_token() { # client-id secret
  curl -sS -m 20 -X POST "$TOKEN_URL" -d grant_type=client_credentials -d "client_id=$1" \
    -d "client_secret=$2" | jq -r '.access_token // empty'
}

jwt_claim() { # token jq-filtresi
  local payload
  payload=$(cut -d. -f2 <<<"$1" | tr '_-' '/+')
  while (( ${#payload} % 4 )); do payload+="="; done
  base64 -d <<<"$payload" 2>/dev/null | jq -r "$2"
}

# grpc TOKEN HEDEF YÖNTEM JSON  (JSON stdin'den; akış istekleri için birden çok JSON art arda verilebilir)
# gRPC şeması sözleşme dosyalarından okunur (imaja kopyalanan contracts/*.proto): sunucu yansıtması (reflection)
# kurye rolüyle reddedildiği için kullanılmaz.
grpc() {
  local token="$1" target="$2" method="$3" data="$4" proto
  case "$method" in
    routemesh.location.*) proto=location.proto ;;
    routemesh.pricing.*) proto=pricing.proto ;;
    routemesh.ledger.*) proto=ledger.proto ;;
    *) echo "bilinmeyen gRPC yöntemi: $method" >&2; return 2 ;;
  esac
  grpcurl -plaintext -max-time 30 -import-path /e2e/proto -proto "$proto"     -H "authorization: Bearer $token" -d @ "$target" "$method" <<<"$data"
}

expect_status() { # beklenen-kod ... (STATUS bunlardan biri olmalı)
  local code
  for code in "$@"; do [[ "$STATUS" == "$code" ]] && return 0; done
  fail "HTTP $STATUS döndü (beklenen: $*) gövde: ${BODY:0:300}"
}
