# Keycloak for Render (render.yaml): the `nms` realm baked in, since Render has no volume to mount it from. docker
# compose doesn't use this — it mounts keycloak/ instead.
FROM quay.io/keycloak/keycloak:26.8.0
COPY --chown=1000:0 nms-realm.json /opt/keycloak/realm-template.json
COPY --chown=1000:0 render-start.sh /opt/keycloak/render-start.sh
ENTRYPOINT ["/bin/bash", "/opt/keycloak/render-start.sh"]
