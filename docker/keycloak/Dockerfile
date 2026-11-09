FROM quay.io/keycloak/keycloak:26.8.2 AS builder

ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2

COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY routemesh-realm.json /opt/keycloak/data/import/routemesh-realm.json

ENV KC_DB=postgres

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
