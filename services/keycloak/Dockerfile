# syntax=docker/dockerfile:1.7

# Multi-stage Keycloak image with the arguslog realm baked in. The "build" stage runs
# `kc.sh build` to pre-bundle the postgres provider so production starts in optimized mode
# without a slow first-request build pass.
#
# Realm import: the JSON sits at /opt/keycloak/data/import/ during build, and KC_BOOTSTRAP_*
# env vars seed the temporary admin user so the operator can log in to the master realm and
# rotate before going live.

FROM quay.io/keycloak/keycloak:26.1 AS builder

# Pre-bundle the postgres feature so the runtime image doesn't need to rebuild on every boot.
# Anything else (metrics, health) is on by default.
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

WORKDIR /opt/keycloak

# Copy the realm before `kc.sh build` so it's part of the optimized image. The runtime stage
# imports it on first boot via --import-realm.
COPY services/keycloak/realm/arguslog-realm.json /opt/keycloak/data/import/arguslog-realm.json

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.1 AS runtime
COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Spring Boot's resource server fetches JWKS from KC_HOSTNAME's well-known endpoint, so this
# value matters: set it to the public auth subdomain in production. Staging may run on the
# Railway-issued hostname instead.
ENV KC_DB=postgres
ENV KC_HOSTNAME_STRICT=false
ENV KC_HTTP_ENABLED=true
ENV KC_PROXY_HEADERS=xforwarded
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

EXPOSE 8080

# `start --optimized` skips the build pass (already done above) and trusts the bundled
# providers; --import-realm reads any *.json in /opt/keycloak/data/import/ on first boot
# and is a no-op if the realm already exists.
ENTRYPOINT ["sh", "-c", "exec /opt/keycloak/bin/kc.sh start --optimized --import-realm"]
