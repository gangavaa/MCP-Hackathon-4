# The whole app in one image: the Angular build (dashboard + chat panel) served by the Spring Boot backend on :4300.

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests && cp target/ask-dashboard-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app.jar app.jar
COPY --from=frontend /src/frontend/dist ./frontend/dist
ENV FRONTEND_DIST=/app/frontend/dist/dashboard/browser \
    PANEL_FILE=/app/frontend/dist/panel/index.html
EXPOSE 4300
ENTRYPOINT ["java", "-jar", "app.jar"]
