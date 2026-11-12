# syntax=docker/dockerfile:1.7
#
# 12 uygulamanın ortak imajı. Bağlam deponun köküdür:
#   docker build -f docker/service.Dockerfile --build-arg MODULE=ledger-service --build-arg HTTP_PORT=8090 .
#
# "build" aşaması MODULE'den bağımsızdır: tüm reaktör bir kez derlenir, 12 imajın hepsi aynı önbellekli katmanı
# kullanır. Maven bağımlılıkları BuildKit önbellek bağlamasındadır (imaja girmez, derlemeler arasında kalır).

# maven:3.9.11-eclipse-temurin-21
FROM maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /src
COPY . .
# Testler Docker gerektirir (Testcontainers); imaj derlemesinde derlenir ama çalıştırılmaz, doğrulama CI'dadır
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

# Boot'un "tools" kipi katmanlı çıkarım yapar: bağımlılıklar nadiren, uygulama sık değişir
FROM build AS extract
ARG MODULE
RUN set -eu; \
    jar="$(ls "/src/${MODULE}/target/${MODULE}"-*.jar | grep -v '\.original$' | head -n 1)"; \
    java -Djarmode=tools -jar "$jar" extract --layers --destination /extracted; \
    mv /extracted/application/*.jar /extracted/application/app.jar

# eclipse-temurin:21.0.8_9-jre-noble
FROM eclipse-temurin@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd AS runtime
ARG HTTP_PORT
ENV HTTP_PORT=${HTTP_PORT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
# Geliştirme/test SMS sink hacminin (yalnızca notification-service bağlar) bağlama noktası, uygulama kullanıcısına ait
RUN mkdir /sms && chown app:app /sms
WORKDIR /app
COPY --from=extract --chown=app:app /extracted/dependencies/ ./
COPY --from=extract --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /extracted/application/ ./
USER app

EXPOSE ${HTTP_PORT}
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
    CMD curl -fsS "http://localhost:${HTTP_PORT}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
