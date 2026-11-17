# syntax=docker/dockerfile:1
FROM node:24.13.0-bookworm-slim AS angular-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/angular.json frontend/tsconfig.json ./
COPY frontend/src/ src/
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS quarkus-build
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build/backend
COPY --chmod=755 backend/mvnw ./
COPY backend/.mvn/ .mvn/
COPY backend/pom.xml ./
COPY backend/src/main/ src/main/
COPY --from=angular-build /build/frontend/dist/piloto/browser/ /build/frontend/dist/piloto/browser/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=quarkus-build /build/backend/target/quarkus-app/ ./
USER 10001:10001
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
