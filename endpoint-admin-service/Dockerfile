## Build Stage
# Faz 22.1.1b: multi-module build — common-auth + endpoint-admin-service
# (Codex 019df86f III review build-fix absorb 2026-05-05)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# common-auth dependency için pom + src
COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src

# #1154 PR-2b: common-export dependency (shared CSV/Excel exporters)
COPY common-export/pom.xml ./common-export/pom.xml
COPY common-export/src ./common-export/src

# endpoint-admin-service pom + src
COPY endpoint-admin-service/pom.xml ./endpoint-admin-service/pom.xml
COPY endpoint-admin-service/src ./endpoint-admin-service/src

# common-auth + common-export'u önce install et (Maven local repo cache; bunlar
# Maven Central'da yok — temiz Docker build için zorunlu)
RUN mvn -f common-auth/pom.xml clean install -Dmaven.test.skip=true -q
RUN mvn -f common-export/pom.xml clean install -Dmaven.test.skip=true -q

# Sonra endpoint-admin-service'i package
RUN mvn -f endpoint-admin-service/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/endpoint-admin-service/target/*.jar app.jar
RUN chown app:app app.jar

EXPOSE 8096 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]
