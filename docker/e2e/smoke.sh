#!/usr/bin/env bash
# RouteMesh uçtan uca duman testi: ön kontroller + tam mutlu yol (teklif -> ödeme -> depo -> atama -> canlı takip ->
# OTP'li teslimat -> hakediş -> cüzdan/arama/bildirim). Her adım PASS/FAIL + süre yazar; ilk hatada kalan adımlar
# SKIP olur ve çıkış kodu 1'dir.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

# Sabit demo verisi (realm içe aktarımı)
MERCHANT_ID="00000000-0000-4000-8000-000000000002"
DEPOT_LAT="41.0082"; DEPOT_LON="28.9784"       # workflow/location ayarlarındaki depo
ORIGIN_LAT="41.01"; ORIGIN_LON="28.97"
DEST_LAT="41.05"; DEST_LON="29.03"            # teslimat noktası (teklifin varış noktası)
NEAR_DEST_LAT="41.0508"                       # varışa ~89 m: yaklaşma yarıçapı (100 m) içinde, PoD sınırı (50 m) dışında
RUN_ID="$(date +%s)$RANDOM"
RECIPIENT_PHONE="+9055$(printf '%08d' $((RANDOM * 1000 + RANDOM % 1000)))"
SESSION_EPOCH="$(date +%s)"
SEQ=0

# ---------------------------------------------------------------------------------------------------------
# Ön kontroller
# ---------------------------------------------------------------------------------------------------------

step_gateway() {
  retry 120 3 curl -fsS -m 5 -o /dev/null "$GATEWAY/actuator/health" || fail "gateway sağlıklı değil ($GATEWAY)" || return 1
  note "gateway sağlıklı"
}

step_service_accounts() {
  local entry id secret token roles missing=0
  for entry in workflow-service surge-service notification-service search-service payment-service location-service; do
    id="$entry"; secret="${entry}-dev-secret"
    token=$(client_token "$id" "$secret")
    if [[ -z "$token" ]]; then note "$id: token alınamadı"; missing=1; continue; fi
    roles=$(jwt_claim "$token" '[.realm_access.roles[]? | select(test("^[A-Z]+$"))] | join(",")')
    note "$id: token alındı (iss=$(jwt_claim "$token" .iss), roller=[${roles}])"
  done
  (( missing == 0 )) || fail "bazı servis hesapları Keycloak'tan token alamadı" || return 1
  # Mutlu yolun çağırdığı hesaplar WORKFLOW rolü taşımalı
  WORKFLOW_TOKEN=$(client_token workflow-service workflow-service-dev-secret)
  [[ "$(jwt_claim "$WORKFLOW_TOKEN" '.realm_access.roles | index("WORKFLOW") != null')" == true ]] \
    || fail "workflow-service token'ında WORKFLOW rolü yok" || return 1
}

step_connectors() {
  local name state
  for name in shipment-outbox ledger-outbox payment-outbox fleet-outbox; do
    retry 60 3 bash -c "curl -fsS -m 5 '$CONNECT/connectors/$name/status' | jq -e '.connector.state==\"RUNNING\" and ([.tasks[].state]|length>0) and ([.tasks[].state]|all(.==\"RUNNING\"))' >/dev/null" \
      || fail "$name bağlayıcısı RUNNING değil: $(curl -s -m 5 "$CONNECT/connectors/$name/status" | head -c 300)" || return 1
    note "$name: RUNNING"
  done
}

step_infra_ready() {
  # Kafka konuları: e2e servisi kafka-init'in başarıyla bitmesine bağlıdır (docker-compose). Konulardan akışın
  # gerçekten tüketildiği 'arama projeksiyonu' ve 'bildirim' adımlarıyla da kanıtlanır.
  note "Kafka konuları: kafka-init tamamlandı (compose bağımlılığı); tüketim akışı arama ve bildirim adımlarında doğrulanır"
  [[ -r "$SMS_SINK" ]] || fail "SMS sink dosyası okunamıyor: $SMS_SINK (notification-service sink-file ve paylaşılan hacim?)" || return 1
  note "SMS sink dosyası okunabilir ($SMS_SINK)"
}

# ---------------------------------------------------------------------------------------------------------
# Mutlu yol
# ---------------------------------------------------------------------------------------------------------

step_tokens() {
  MERCHANT_TOKEN=$(user_token merchant merchant-dev)
  COURIER_TOKEN=$(user_token courier courier-dev)
  WAREHOUSE_TOKEN=$(user_token warehouse warehouse-dev)
  [[ -n "$MERCHANT_TOKEN" && -n "$COURIER_TOKEN" && -n "$WAREHOUSE_TOKEN" ]] \
    || fail "merchant/courier/warehouse token'ları alınamadı" || return 1
  COURIER_ID=$(jwt_claim "$COURIER_TOKEN" .sub)
  [[ "$(jwt_claim "$MERCHANT_TOKEN" .sub)" == "$MERCHANT_ID" ]] || fail "merchant sub beklenenden farklı" || return 1
  note "merchant=$MERCHANT_ID courier=$COURIER_ID"
}

# Konum paketi (gRPC istemci akışı); kurye token'ının sub değeri courier_id olmak zorundadır
ping_location() { # enlem boylam
  SEQ=$((SEQ + 1))
  local out
  out=$(grpc "$COURIER_TOKEN" "$LOCATION_GRPC" routemesh.location.v1.LocationService/StreamCourierLocations \
    "{\"courier_id\":\"$COURIER_ID\",\"location\":{\"latitude\":$1,\"longitude\":$2},\"session_epoch\":$SESSION_EPOCH,\"sequence_number\":$SEQ}") \
    || { fail "konum paketi gönderilemedi: ${out:0:200}"; return 1; }
  [[ "$(jq -r '.accepted // "0"' <<<"$out")" != "0" ]] || fail "konum paketi kabul edilmedi: $out" || return 1
}

wallet_balance() { # token -> bakiye (minor)
  http GET "$GATEWAY/api/v1/wallets/me" -H "$(auth "$1")"
  [[ "$STATUS" == 200 ]] || { echo "-1"; return 1; }
  jq -r '.balance.amountMinor // 0' <<<"$BODY"
}

step_baseline() {
  MERCHANT_WALLET_BEFORE=$(wallet_balance "$MERCHANT_TOKEN") || fail "merchant cüzdanı okunamadı (HTTP $STATUS)" || return 1
  COURIER_WALLET_BEFORE=$(wallet_balance "$COURIER_TOKEN") || fail "kurye cüzdanı okunamadı (HTTP $STATUS)" || return 1
  note "başlangıç bakiyeleri: merchant=$MERCHANT_WALLET_BEFORE kurye=$COURIER_WALLET_BEFORE"
}

# Önceki (yarım kalmış) bir koşudan kurye BUSY kalmış olabilir: OPS aktif gönderiyi iptal eder, kurye serbest kalır
release_stale_courier() {
  http GET "$GATEWAY/api/v1/fleet/me" -H "$(auth "$COURIER_TOKEN")"
  [[ "$(jq -r '.status // empty' <<<"$BODY")" == BUSY ]] || return 0
  local stale
  stale=$(jq -r '.activeShipmentId // empty' <<<"$BODY")
  note "kurye önceki koşudan BUSY (gönderi $stale): OPS iptal ediyor"
  local ops
  ops=$(user_token ops ops-dev)
  http POST "$GATEWAY/api/v1/shipments/$stale/transitions" -H "$(auth "$ops")" -H "Idempotency-Key: e2e-clean-$RUN_ID"     -H 'Content-Type: application/json' -d '{"toStatus":"CANCELLED","reason":"e2e cleanup"}'
  # Gönderi iptali kuryeyi kendiliğinden serbest bırakmaz (yalnızca dağıtım iş akışının telafisi bırakır): test
  # hesabı fleet gRPC ile bırakır
  grpc "$WORKFLOW_TOKEN" "$FLEET_GRPC" routemesh.fleet.v1.FleetService/ReleaseCourier     "{\"courier_id\":\"$COURIER_ID\",\"shipment_id\":\"$stale\"}" >/dev/null 2>&1
  free() {
    http GET "$GATEWAY/api/v1/fleet/me" -H "$(auth "$COURIER_TOKEN")"
    [[ "$(jq -r '.status // empty' <<<"$BODY")" != BUSY ]]
  }
  retry 45 3 free || fail "kurye hâlâ BUSY (önceki koşudan kalan gönderi $stale temizlenemedi; yığını 'down -v' ile sıfırlayın)" || return 1
}

step_courier_online() {
  release_stale_courier || return 1
  http PUT "$GATEWAY/api/v1/fleet/me/status" -H "$(auth "$COURIER_TOKEN")" -H 'Content-Type: application/json' \
    -d '{"status":"IDLE"}'
  expect_status 200 || return 1
  [[ "$(jq -r .status <<<"$BODY")" == IDLE ]] || fail "kurye IDLE olmadı: $BODY" || return 1
  ping_location "$DEPOT_LAT" "$DEPOT_LON" || return 1
  note "kurye IDLE ve depoda (konum paketi kabul edildi)"
}

step_quote() {
  local out
  out=$(grpc "$WORKFLOW_TOKEN" "$PRICING_GRPC" routemesh.pricing.v1.PricingService/CreateQuote \
    "{\"idempotency_key\":\"e2e-$RUN_ID\",\"merchant_id\":\"$MERCHANT_ID\",\"origin\":{\"latitude\":$ORIGIN_LAT,\"longitude\":$ORIGIN_LON},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LON},\"service_level\":\"SERVICE_LEVEL_STANDARD\",\"weight_grams\":1500}") \
    || { fail "teklif alınamadı: ${out:0:300}"; return 1; }
  QUOTE_ID=$(jq -r '.quote.id' <<<"$out")
  AMOUNT=$(jq -r '.quote.finalAmount.amountMinor' <<<"$out")
  [[ -n "$QUOTE_ID" && "$AMOUNT" =~ ^[0-9]+$ ]] || fail "teklif yanıtı beklenmedik: ${out:0:300}" || return 1
  note "teklif $QUOTE_ID tutar=$AMOUNT TRY (minor)"
}

step_create_shipment() {
  http POST "$GATEWAY/api/v1/shipments" -H "$(auth "$MERCHANT_TOKEN")" -H "Idempotency-Key: e2e-ship-$RUN_ID" \
    -H 'Content-Type: application/json' \
    -d "{\"quoteId\":\"$QUOTE_ID\",\"recipientName\":\"Ada Lovelace\",\"recipientPhone\":\"$RECIPIENT_PHONE\",\"deliveryAddress\":\"E2E Mah. 1\"}"
  expect_status 202 || return 1
  SHIPMENT_ID=$(jq -r .shipmentId <<<"$BODY")
  PSP_PAYMENT_ID=$(jq -r '.checkoutUrl | split("/") | last' <<<"$BODY")
  [[ -n "$SHIPMENT_ID" && "$SHIPMENT_ID" != null && -n "$PSP_PAYMENT_ID" ]] || fail "gönderi yanıtı beklenmedik: $BODY" || return 1
  note "gönderi $SHIPMENT_ID, PSP ödemesi $PSP_PAYMENT_ID, alıcı $RECIPIENT_PHONE"
}

shipment_status() { # token -> durum
  http GET "$GATEWAY/api/v1/shipments/$SHIPMENT_ID" -H "$(auth "$1")"
  jq -r '.status // empty' <<<"$BODY"
}

wait_status() { # durum [token] [zaman-aşımı]
  local want="$1" token="${2:-$MERCHANT_TOKEN}" timeout="${3:-60}" now=""
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    now=$(shipment_status "$token")
    [[ "$now" == "$want" ]] && return 0
    (( $(date +%s) >= deadline )) && break
    sleep 2
  done
  fail "gönderi $want olmadı (son durum: ${now:-bilinmiyor})"
}

step_payment() {
  http POST "$PSP_SIM/v1/sim/payments/$PSP_PAYMENT_ID/complete" -H 'Content-Type: application/json' -d '{}'
  expect_status 200 202 || return 1
  wait_status PAID "$MERCHANT_TOKEN" 90 || return 1
  note "PSP tamamlandı, webhook -> yetkilendirme -> tahsilat -> PAID"
}

step_warehouse() {
  http POST "$GATEWAY/api/v1/warehouse/shipments/$SHIPMENT_ID/receive" -H "$(auth "$WAREHOUSE_TOKEN")" \
    -H "Idempotency-Key: e2e-recv-$RUN_ID"
  expect_status 200 || return 1
  [[ "$(jq -r .status <<<"$BODY")" == AT_WAREHOUSE ]] || fail "AT_WAREHOUSE olmadı: $BODY" || return 1
  ping_location "$DEPOT_LAT" "$DEPOT_LON" || return 1   # kurye konumu taze kalsın (60 sn)
  http POST "$GATEWAY/api/v1/warehouse/shipments/$SHIPMENT_ID/ready" -H "$(auth "$WAREHOUSE_TOKEN")" \
    -H "Idempotency-Key: e2e-ready-$RUN_ID"
  expect_status 200 || return 1
  [[ "$(jq -r .status <<<"$BODY")" == READY_FOR_DISPATCH ]] || fail "READY_FOR_DISPATCH olmadı: $BODY" || return 1
  note "depo kabul ve hazırlama tamam"
}

OFFER_ID=""
find_offer() {
  ping_location "$DEPOT_LAT" "$DEPOT_LON" >/dev/null 2>&1   # dağıtım iş akışı en yakın kuryeyi konumdan bulur
  http GET "$GATEWAY/api/v1/fleet/me/offers" -H "$(auth "$COURIER_TOKEN")"
  [[ "$STATUS" == 200 ]] || return 1
  OFFER_ID=$(jq -r --arg s "$SHIPMENT_ID" '[.[] | select(.shipmentId==$s and .status=="OFFERED")][0].offerId // empty' <<<"$BODY")
  [[ -n "$OFFER_ID" ]]
}

step_assignment() {
  retry 120 3 find_offer || fail "kuryeye teklif gelmedi (dağıtım iş akışı/fleet)" || return 1
  note "teklif geldi: $OFFER_ID"
  http POST "$GATEWAY/api/v1/fleet/offers/$OFFER_ID/accept" -H "$(auth "$COURIER_TOKEN")"
  expect_status 200 || return 1
  wait_status ASSIGNED "$MERCHANT_TOKEN" 60 || return 1
  http GET "$GATEWAY/api/v1/shipments/$SHIPMENT_ID" -H "$(auth "$MERCHANT_TOKEN")"
  [[ "$(jq -r .courierId <<<"$BODY")" == "$COURIER_ID" ]] || fail "gönderiye atanan kurye beklenen değil: $BODY" || return 1
  note "teklif kabul edildi, gönderi ASSIGNED"
}

SSE_FILE=""
# İzleme (watch) Kafka olayıyla kurulur; ASSIGNED'dan hemen sonra akış 404 dönebilir, bu yüzden birkaç kez denenir
open_sse_once() {
  SSE_FILE="$WORK/sse.txt"
  [[ -n "${SSE_PID:-}" ]] && kill "$SSE_PID" 2>/dev/null
  curl -sN -i -m 240 -H "$(auth "$MERCHANT_TOKEN")" -H 'Accept: text/event-stream'     "$GATEWAY/api/v1/tracking/$SHIPMENT_ID/stream" >"$SSE_FILE" 2>"$WORK/sse.err" &
  SSE_PID=$!
  retry 5 1 grep -q ':connected' "$SSE_FILE"
}

step_sse_open() {
  retry 40 1 open_sse_once || fail "SSE bağlantısı kurulamadı: $(head -c 300 "$SSE_FILE") $(head -c 200 "$WORK/sse.err")" || return 1
  note "SSE akışı açık (gateway üzerinden, merchant token'ı)"
}

sse_has() { grep -q "$1" "$SSE_FILE"; }

step_tracking_pickup() {
  sleep 2
  ping_location "$DEPOT_LAT" "$DEPOT_LON" || return 1
  retry 20 1 sse_has 'event:location' || fail "SSE 'location' olayı gelmedi: $(head -c 400 "$SSE_FILE")" || return 1
  retry 20 1 sse_has 'event:approaching' || fail "SSE 'approaching' (alım) olayı gelmedi: $(head -c 400 "$SSE_FILE")" || return 1
  note "SSE: location ve approaching (depoya yaklaşma) geldi"
}

step_out_for_delivery() {
  http POST "$GATEWAY/api/v1/shipments/$SHIPMENT_ID/transitions" -H "$(auth "$COURIER_TOKEN")" \
    -H "Idempotency-Key: e2e-ofd-$RUN_ID" -H 'Content-Type: application/json' \
    -d '{"toStatus":"OUT_FOR_DELIVERY","expectedFromStatus":"ASSIGNED"}'
  expect_status 200 || return 1
  [[ "$(jq -r .status <<<"$BODY")" == OUT_FOR_DELIVERY ]] || fail "OUT_FOR_DELIVERY olmadı: $BODY" || return 1
  note "kurye yola çıktı (OUT_FOR_DELIVERY)"
}

step_tracking_delivery() {
  sleep 3                                              # izleme (watch) yeniden kurulsun
  ping_location "$NEAR_DEST_LAT" "$DEST_LON" || return 1   # varışa ~89 m
  sleep 3
  ping_location "$DEST_LAT" "$DEST_LON" || return 1        # varış noktası
  retry 25 1 bash -c "grep -c 'event:approaching' '$SSE_FILE' | grep -qE '^[2-9]'" \
    || fail "teslimat 'approaching' olayı gelmedi: $(grep -A1 'event:' "$SSE_FILE" | head -c 600)" || return 1
  local locations
  locations=$(grep -c 'event:location' "$SSE_FILE")
  (( locations >= 2 )) || fail "yeterli 'location' olayı yok ($locations)" || return 1
  note "SSE: $locations location olayı, alım ve teslimat approaching olayları geldi"
}

step_otp() {
  OTP=""
  read_otp() {
    OTP=$(jq -rs --arg p "$RECIPIENT_PHONE" \
      '[.[] | select(.phone==$p and (.text | test("teslimat kodunuz")))] | last | .text // empty' "$SMS_SINK" 2>/dev/null \
      | grep -oE '[0-9]{4,8}' | head -n 1)
    [[ -n "$OTP" ]]
  }
  retry 60 2 read_otp || fail "alıcı OTP SMS'i sink dosyasında bulunamadı (notification-service OTP üretmedi?)" || return 1
  note "OTP SMS'i sink dosyasından okundu (kod ${#OTP} haneli; değeri günlüğe yazılmaz)"
}

step_pod_delivery() {
  printf '\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01e2e-photo' >"$WORK/photo.jpg"
  printf '\x89PNG\r\n\x1a\ne2e-signature' >"$WORK/signature.png"
  http POST "$GATEWAY/api/v1/shipments/$SHIPMENT_ID/pod/files" -H "$(auth "$COURIER_TOKEN")" \
    -F kind=PHOTO -F "file=@$WORK/photo.jpg;type=image/jpeg"
  expect_status 201 || return 1
  PHOTO_ID=$(jq -r .fileId <<<"$BODY")
  http POST "$GATEWAY/api/v1/shipments/$SHIPMENT_ID/pod/files" -H "$(auth "$COURIER_TOKEN")" \
    -F kind=SIGNATURE -F "file=@$WORK/signature.png;type=image/png"
  expect_status 201 || return 1
  SIGNATURE_ID=$(jq -r .fileId <<<"$BODY")
  note "PoD dosyaları yüklendi (fotoğraf ve imza)"

  # 50 m kuralı: varış noktasının ~89 m uzağından teslimat reddedilmeli
  http POST "$GATEWAY/api/v1/shipments/$SHIPMENT_ID/deliver" -H "$(auth "$COURIER_TOKEN")" \
    -H "Idempotency-Key: e2e-too-far-$RUN_ID" -H 'Content-Type: application/json' \
    -d "{\"photoFileId\":\"$PHOTO_ID\",\"latitude\":$NEAR_DEST_LAT,\"longitude\":$DEST_LON,\"recipientName\":\"Ada Lovelace\",\"otp\":\"$OTP\"}"
  [[ "$STATUS" == 4* ]] || fail "89 m uzaktan teslimat reddedilmedi (HTTP $STATUS): ${BODY:0:200}" || return 1
  note "50 m kuralı: uzaktan teslimat reddedildi (HTTP $STATUS)"

  http POST "$GATEWAY/api/v1/shipments/$SHIPMENT_ID/deliver" -H "$(auth "$COURIER_TOKEN")" \
    -H "Idempotency-Key: e2e-deliver-$RUN_ID" -H 'Content-Type: application/json' \
    -d "{\"photoFileId\":\"$PHOTO_ID\",\"signatureFileId\":\"$SIGNATURE_ID\",\"latitude\":$DEST_LAT,\"longitude\":$DEST_LON,\"accuracyMeters\":5.0,\"recipientName\":\"Ada Lovelace\",\"otp\":\"$OTP\"}"
  expect_status 200 || return 1
  [[ "$(jq -r .status <<<"$BODY")" == DELIVERED ]] || fail "DELIVERED olmadı: $BODY" || return 1
  note "OTP ve fotoğraf/imza ile teslimat tamam: DELIVERED"
}

step_settlement() {
  local merchant_want=$(( AMOUNT * 75 / 100 )) courier_want=$(( AMOUNT * 18 / 100 ))
  local platform_want=$(( AMOUNT - merchant_want - courier_want ))
  local merchant_now courier_now
  settled() {
    merchant_now=$(wallet_balance "$MERCHANT_TOKEN") || return 1
    courier_now=$(wallet_balance "$COURIER_TOKEN") || return 1
    (( merchant_now - MERCHANT_WALLET_BEFORE == merchant_want && courier_now - COURIER_WALLET_BEFORE == courier_want ))
  }
  retry 90 3 settled || fail "hakediş cüzdanlara yansımadı: merchant +$((merchant_now - MERCHANT_WALLET_BEFORE)) (beklenen $merchant_want), kurye +$((courier_now - COURIER_WALLET_BEFORE)) (beklenen $courier_want)" || return 1
  note "tutar $AMOUNT: satıcı +$merchant_want (%75), kurye +$courier_want (%18)"

  local out platform escrow
  out=$(grpc "$WORKFLOW_TOKEN" "$LEDGER_GRPC" routemesh.ledger.v1.LedgerService/GetBalance \
    "{\"account_code\":\"REVENUE:PLATFORM_FEE\",\"shipment_id\":\"$SHIPMENT_ID\"}") || fail "platform payı okunamadı: ${out:0:200}" || return 1
  platform=$(jq -r '.balance.amountMinor // "0"' <<<"$out")
  out=$(grpc "$WORKFLOW_TOKEN" "$LEDGER_GRPC" routemesh.ledger.v1.LedgerService/GetBalance \
    "{\"account_code\":\"LIABILITY:ESCROW\",\"shipment_id\":\"$SHIPMENT_ID\"}") || fail "escrow okunamadı: ${out:0:200}" || return 1
  escrow=$(jq -r '.balance.amountMinor // "0"' <<<"$out")
  [[ "$platform" == "$platform_want" ]] || fail "platform payı $platform (beklenen $platform_want, %7)" || return 1
  [[ "$escrow" == 0 ]] || fail "escrow sıfırlanmadı: $escrow" || return 1
  note "platform +$platform_want (%7), escrow 0 (defter dengede)"
}

step_search() {
  found() {
    http GET "$GATEWAY/api/v1/search/shipments?status=DELIVERED&limit=100" -H "$(auth "$MERCHANT_TOKEN")"
    [[ "$STATUS" == 200 ]] || return 1
    jq -e --arg id "$SHIPMENT_ID" --arg c "$COURIER_ID" \
      '.items[] | select(.id==$id and .status=="DELIVERED" and .courierId==$c)' <<<"$BODY" >/dev/null
  }
  retry 60 2 found || fail "CQRS arama sonucunda DELIVERED gönderi yok (Kafka -> search-service projeksiyonu?)" || return 1
  note "arama: gönderi DELIVERED ve kurye ile birlikte listede"
}

step_notifications() {
  has_template() { # token şablon
    http GET "$GATEWAY/api/v1/notifications?limit=100" -H "$(auth "$1")"
    [[ "$STATUS" == 200 ]] || return 1
    jq -e --arg s "$SHIPMENT_ID" --arg t "$2" '.items[] | select(.shipmentId==$s and .template==$t)' <<<"$BODY" >/dev/null
  }
  retry 60 2 has_template "$MERCHANT_TOKEN" SHIPMENT_DELIVERED || fail "merchant kutusunda SHIPMENT_DELIVERED yok" || return 1
  retry 30 2 has_template "$MERCHANT_TOKEN" COURIER_APPROACHING || fail "merchant kutusunda COURIER_APPROACHING yok" || return 1
  retry 30 2 has_template "$COURIER_TOKEN" NEW_OFFER || fail "kurye kutusunda NEW_OFFER yok" || return 1
  note "bildirim kutuları: merchant (SHIPMENT_DELIVERED, COURIER_APPROACHING), kurye (NEW_OFFER)"
}

# Bilinen açık: teslimat (ve iptal) sonrası kuryeyi serbest bırakan bir akış yok; yalnızca dağıtım iş akışının telafisi
# ReleaseCourier çağırır. Kurye BUSY kalırsa yeni teklif alamaz. Düzelene kadar WARN olarak görünür.
step_courier_released() {
  freed() {
    http GET "$GATEWAY/api/v1/fleet/me" -H "$(auth "$COURIER_TOKEN")"
    [[ "$(jq -r '.status // empty' <<<"$BODY")" != BUSY ]]
  }
  retry 15 3 freed || fail "BİLİNEN AÇIK: kurye teslimattan sonra BUSY kaldı (aktif gönderi: $(jq -r '.activeShipmentId // "-"' <<<"$BODY")); sonraki koşu OPS ile temizler" || return 1
  note "kurye teslimattan sonra IDLE'a döndü"
}

echo "RouteMesh E2E duman testi | gateway=$GATEWAY | koşu=$RUN_ID"
echo "--- Ön kontroller"
run_step "Gateway hazır"                           step_gateway
run_step "Servis hesapları Keycloak'tan token alıyor" step_service_accounts
run_step "Debezium bağlayıcıları RUNNING"            step_connectors
run_step "Kafka konuları ve SMS sink hazır"          step_infra_ready
echo "--- Mutlu yol"
run_step "Kullanıcı token'ları (merchant, kurye, depo)" step_tokens
run_step "Cüzdan başlangıç bakiyeleri"              step_baseline
run_step "Kurye çevrimiçi ve depoda (location gRPC)" step_courier_online
run_step "Fiyat teklifi (pricing gRPC)"             step_quote
run_step "Gönderi oluşturma (gateway -> workflow)"  step_create_shipment
run_step "Ödeme: PSP tamamlama, webhook, PAID"      step_payment
run_step "Depo kabul ve hazırlama"                  step_warehouse
run_step "Otomatik atama (workflow/fleet teklifi)"  step_assignment
run_step "Canlı takip (SSE) bağlantısı"             step_sse_open
run_step "Canlı takip: alım yaklaşması"             step_tracking_pickup
run_step "Yola çıkış (OUT_FOR_DELIVERY)"            step_out_for_delivery
run_step "Canlı takip: teslimat yaklaşması"         step_tracking_delivery
run_step "Alıcı OTP'si (SMS sink dosyası)"          step_otp
run_step "50 m PoD ve teslimat (DELIVERED)"         step_pod_delivery
run_step "Ledger hakediş dağıtımı %75/%18/%7"       step_settlement
run_step "CQRS arama (/search/shipments)"           step_search
run_step "Bildirim kutuları (/notifications)"       step_notifications
run_soft "Kurye teslimattan sonra serbest kalıyor (bilinen açık)" step_courier_released

print_summary
(( FAILED == 0 ))
