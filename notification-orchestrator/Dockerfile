## notification-orchestrator (Faz 23.1 Kernel — ADR-0013)
## Codex thread 019df86f-89aa-7200-bb6c-b7b903860148

## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY notification-orchestrator/pom.xml ./notification-orchestrator/pom.xml
RUN mvn -f notification-orchestrator/pom.xml dependency:go-offline -q || true

COPY notification-orchestrator/src ./notification-orchestrator/src
RUN mvn -f notification-orchestrator/pom.xml clean package -Dmaven.test.skip=true -q

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 --system app \
    && useradd --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/notification-orchestrator/target/*.jar app.jar
RUN chown app:app app.jar

EXPOSE 8089 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]
