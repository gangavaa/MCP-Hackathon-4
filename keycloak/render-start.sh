#!/bin/bash
# Starts Keycloak on Render. The realm names the dashboard at http://localhost:4300 (redirect URIs, web origins,
# sign-out); APP_URL swaps in where the dashboard really lives before the realm is imported. Plain bash — the
# Keycloak image has no sed.
set -euo pipefail

realm=$(</opt/keycloak/realm-template.json)
realm=${realm//http:\/\/localhost:4300/${APP_URL:?APP_URL must name the dashboard, e.g. https://nms-app.onrender.com}}
mkdir -p /opt/keycloak/data/import
printf '%s\n' "$realm" > /opt/keycloak/data/import/nms-realm.json

# Render terminates TLS and forwards X-Forwarded-*, so Keycloak itself speaks plain HTTP; RENDER_EXTERNAL_URL is this
# service's public https URL.
exec /opt/keycloak/bin/kc.sh start --optimized --import-realm \
  --http-enabled=true \
  --hostname="${RENDER_EXTERNAL_URL:?}" \
  --proxy-headers=xforwarded \
  --http-port="${PORT:-8080}"
