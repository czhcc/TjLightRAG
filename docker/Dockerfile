FROM node:24-bookworm-slim AS frontend
RUN --mount=type=secret,id=proxy_ca \
    if [ -f /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; npm install -g pnpm@11.25.0 --fetch-retries=0 --fetch-timeout=20000
WORKDIR /build/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN --mount=type=secret,id=proxy_ca \
    if [ -f /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca; fi; pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist/ bootstart/src/main/resources/static/
RUN --mount=type=secret,id=maven_settings --mount=type=secret,id=java_cacerts \
    if [ -f /run/secrets/maven_settings ]; then \
      mvn -s /run/secrets/maven_settings -Djavax.net.ssl.trustStore=/run/secrets/java_cacerts -B -DskipTests package; \
    else mvn -B -DskipTests package; fi

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /build/backend/bootstart/target/bootstart-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
