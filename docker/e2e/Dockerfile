# syntax=docker/dockerfile:1.7
#
# Uçtan uca duman testi çalıştırıcısı: bash, curl, jq ve grpcurl içeren hafif Alpine imajı.
#   docker compose --profile e2e run --rm e2e
# Bağlam deponun köküdür (gRPC sözleşme dosyaları contracts/ altından kopyalanır).

# fullstorydev/grpcurl:v1.9.3
FROM fullstorydev/grpcurl@sha256:085e183ca334eb4e81ca81ee12cbb2b2737505d1d77f5e33dabc5d066593d998 AS grpcurl

# alpine:3.22.1
FROM alpine@sha256:4bcff63911fcb4448bd4fdacec207030997caf25e9bea4045fa6c8c44de311d1
RUN apk add --no-cache bash curl jq coreutils

COPY --from=grpcurl /bin/grpcurl /usr/local/bin/grpcurl

# SMS sink dosyası uygulamanın uid'iyle (10001) ve 0600 izniyle yazılır; çalıştırıcı aynı uid ile okur, başka
# hiçbir servis o hacmi bağlamaz.
RUN addgroup -S -g 10001 e2e && adduser -S -u 10001 -G e2e e2e
COPY contracts/src/main/proto/ /e2e/proto/
COPY --chmod=755 docker/e2e/lib.sh docker/e2e/smoke.sh /e2e/
USER e2e
WORKDIR /e2e
ENTRYPOINT ["/e2e/smoke.sh"]
