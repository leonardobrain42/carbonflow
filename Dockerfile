# syntax=docker/dockerfile:1
FROM node:24.13.0-bookworm-slim AS angular-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/angular.json frontend/tsconfig.json ./
COPY frontend/src/ src/
RUN npm run build

FROM maven:3.9.12-eclipse-temurin-21 AS quarkus-build
WORKDIR /build/backend
COPY --chmod=755 backend/mvnw ./
COPY backend/.mvn/ .mvn/
COPY backend/pom.xml ./
COPY backend/src/main/ src/main/
COPY --from=angular-build /build/frontend/dist/piloto/browser/ /build/frontend/dist/piloto/browser/
# Central occasionally rate-limits simultaneous CI builds (HTTP 429).
# Retry only the dependency/build step with forced metadata refresh; fail closed
# after the bounded attempts. No dependency versions or tests are skipped beyond
# the original packaged image build contract.
RUN --mount=type=cache,target=/root/.m2 \
    set -eu; attempt=1; \
    until mvn -B -ntp -U -Dmaven.test.skip=true package; do \
      if [ "$attempt" -ge 4 ]; then echo "Maven build failed after 4 attempts"; exit 1; fi; \
      echo "Maven repository unavailable; retry attempt $((attempt + 1))/4"; \
      sleep $((attempt * 15)); \
      attempt=$((attempt + 1)); \
    done

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=quarkus-build /build/backend/target/quarkus-app/ ./
USER 10001:10001
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
