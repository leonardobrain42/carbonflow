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
# Maven Central sometimes returns HTTP 429 or incorrectly caches missing
# artifacts during simultaneous CI builds. Google hosts a documented mirror
# of Maven Central. Use it only after a bounded primary repository failure;
# never mask Java compilation or test errors as dependency outages.
RUN --mount=type=cache,target=/root/.m2 \
    set -eu; \
    printf '%s\n' '<settings><mirrors><mirror><id>google-maven-central-fallback</id><mirrorOf>central</mirrorOf><url>https://maven-central.storage-download.googleapis.com/maven2/</url></mirror></mirrors></settings>' > /tmp/maven-fallback.xml; \
    attempt=1; \
    while [ "$attempt" -le 4 ]; do \
      if [ "$attempt" -le 2 ]; then \
        if mvn -B -ntp -U -Dmaven.test.skip=true package > /tmp/maven.log 2>&1; then cat /tmp/maven.log; exit 0; fi; \
      else \
        if mvn -B -ntp -U -s /tmp/maven-fallback.xml -Dmaven.test.skip=true package > /tmp/maven.log 2>&1; then cat /tmp/maven.log; exit 0; fi; \
      fi; \
      cat /tmp/maven.log; \
      if ! grep -Eiq 'HTTP Status: 429|HTTP 429|Too Many Requests|Could not transfer artifact|Could not find artifact.* in central|Non-resolvable import POM|Unresolveable build extension' /tmp/maven.log; then \
        echo "Maven failure is not a repository outage. Aborting."; exit 1; \
      fi; \
      if [ "$attempt" -ge 4 ]; then echo "Maven repository failure after 4 attempts"; exit 1; fi; \
      echo "Maven download issue; retry $((attempt + 1))/4"; \
      sleep $((attempt * 10)); \
      attempt=$((attempt + 1)); \
    done; exit 1

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=quarkus-build /build/backend/target/quarkus-app/ ./
USER 10001:10001
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
