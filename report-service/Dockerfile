## Build Stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY common-auth/pom.xml ./common-auth/pom.xml
COPY common-auth/src ./common-auth/src
COPY common-export/pom.xml ./common-export/pom.xml
COPY common-export/src ./common-export/src
COPY report-service/pom.xml ./report-service/pom.xml
COPY report-service/src ./report-service/src

RUN mvn -f common-auth/pom.xml clean install -DskipTests
# board #1154: report-service depends on the shared common-export module
# (com.example:common-export:1.0.0); install it before packaging so the
# clean Docker build can resolve it (it is not in Maven Central).
RUN mvn -f common-export/pom.xml clean install -DskipTests
RUN mvn -f report-service/pom.xml clean package -Dmaven.test.skip=true

## Runtime Stage
FROM eclipse-temurin:21-jre-jammy

# D14: non-root user (K8s PSS restricted + runAsNonRoot uyumlu)
# UID 1000 platform-k8s-gitops Deployment securityContext ile eşleşir
RUN groupadd --gid 1000 --system app \
    && useradd  --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/report-service/target/*.jar app.jar
RUN chown app:app app.jar

# Main app (8095) + management actuator (8081) — application-k8s.yml ile eşleşir
EXPOSE 8095 8081

USER 1000:1000

ENTRYPOINT ["java", "-jar", "app.jar"]
