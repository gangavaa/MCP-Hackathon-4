# Two images from one build: `app` — the REST backend serving the Angular build on :4300 — and `mcp` — the MCP server
# on :4301, which calls the backend's API. docker compose picks each with `target:`.

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests && cp target/nms-dashboard-*.jar /app.jar

FROM maven:3.9-eclipse-temurin-21 AS mcp-build
WORKDIR /src/mcp-server
COPY mcp-server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY mcp-server/src ./src
RUN mvn -q -B package -DskipTests && cp target/nms-dashboard-mcp-*.jar /mcp.jar

FROM eclipse-temurin:21-jre AS mcp
WORKDIR /app
COPY --from=mcp-build /mcp.jar mcp.jar
COPY --from=frontend /src/frontend/dist/panel ./frontend/dist/panel
ENV PANEL_FILE=/app/frontend/dist/panel/index.html
EXPOSE 4301
ENTRYPOINT ["java", "-jar", "mcp.jar"]

FROM eclipse-temurin:21-jre AS app
WORKDIR /app
COPY --from=backend-build /app.jar app.jar
COPY --from=frontend /src/frontend/dist ./frontend/dist
ENV FRONTEND_DIST=/app/frontend/dist/dashboard/browser \
    PANEL_FILE=/app/frontend/dist/panel/index.html
EXPOSE 4300
ENTRYPOINT ["java", "-jar", "app.jar"]
