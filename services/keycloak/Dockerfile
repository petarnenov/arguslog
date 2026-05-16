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

# Render the realm from its template before `kc.sh build` so it's part of the optimized
# image. The runtime stage imports it on first boot via --import-realm.
#
# The realm file is generated (not committed) — local dev runs services/keycloak/render-realm.sh
# from the Makefile. Here we replicate that step inside the build using sed so the prod image
# builds without any host tooling. DEV_HOST is intentionally left as "localhost" in prod
# images: the redirect URIs that matter (auth.arguslog.org, *.staging.up.railway.app) are
# already in the template; localhost is just an extra entry that nobody on the public internet
# can hit.
COPY services/keycloak/realm.template.json /tmp/realm.template.json
# No `rm /tmp/realm.template.json` at the end — the KC 26.1 base image runs as uid 1000 and
# can't delete a root-owned file in /tmp. The temp file never reaches the runtime stage (the
# final image COPYs --from=builder /opt/keycloak/ only), so leaving it in the builder fs is
# free.
RUN mkdir -p /opt/keycloak/data/import \
 && sed 's|__DEV_HOST__|localhost|g' /tmp/realm.template.json \
      > /opt/keycloak/data/import/arguslog-realm.json

# Custom login theme (Arguslog dark + brand green) — referenced from realm.template.json via
# loginTheme="arguslog". Inherits all templates from parent=keycloak; we only ship CSS + logo
# so future KC upgrades don't require keeping FreeMarker templates in sync.
# --chown is mandatory: the Keycloak image runs `kc.sh build` as uid 1000 (keycloak user) and
# COPY without ownership would land files as root, which the keycloak user can't read at
# runtime (and possibly fails the build pass too).
COPY --chown=1000:0 services/keycloak/themes/arguslog /opt/keycloak/themes/arguslog

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.1 AS runtime
COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Social-login (GitHub / Google) IdP patcher — runs from the entrypoint AFTER kc.sh starts.
# Realm imports only happen on first boot; this script is the authoritative way to keep
# long-running prod realms in sync with the env-var-supplied OAuth credentials. See
# SELF_HOSTING.md for the env-var contract.
#
# The script uses /opt/keycloak/bin/kcadm.sh (bundled with the image) instead of curl + jq —
# Keycloak 26.x dropped the UBI-minimal base in favor of a slimmer image with no package
# manager, so installing extra tooling at build time is no longer an option. kcadm.sh is
# Keycloak's own admin CLI; it talks to the same Admin REST API and is the documented way
# to script realm operations from within the container.
COPY --chown=1000:0 services/keycloak/configure-idps.sh /opt/keycloak/configure-idps.sh
COPY --chown=1000:0 services/keycloak/entrypoint.sh     /opt/keycloak/entrypoint.sh

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

# Entrypoint runs the IdP patcher in the background, then exec's kc.sh in the foreground so
# SIGTERM from `docker stop` / Railway shutdown propagates directly to Keycloak.
ENTRYPOINT ["/opt/keycloak/entrypoint.sh"]
