# Keycloak for Render (render.yaml): the `nms` realm baked in, since Render has no volume to mount it from. docker
# compose doesn't use this — it mounts keycloak/ instead.
#
# Built ahead of time (`kc.sh build`) and started with `start --optimized`: `start-dev` re-runs the Quarkus build on
# every boot, which on a free 512 MB instance takes minutes and leaves too little memory for the realm import.
FROM quay.io/keycloak/keycloak:26.8.0 AS builder
ENV KC_DB=dev-file \
    KC_CACHE=local \
    KC_HEALTH_ENABLED=false \
    KC_METRICS_ENABLED=false
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 nms-realm.json /opt/keycloak/realm-template.json
COPY --chown=1000:0 render-start.sh /opt/keycloak/render-start.sh
# Heap and off-heap sized to fit 512 MB with the realm import; Keycloak's default takes 70% of RAM for heap alone.
ENV JAVA_OPTS_KC_HEAP="-Xms64m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1"
ENTRYPOINT ["/bin/bash", "/opt/keycloak/render-start.sh"]
