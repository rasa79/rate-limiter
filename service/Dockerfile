# ---- build stage ----
# RATIONALE: multi-stage build keeps the runtime image to just the JRE and the
# fat jar; the Maven build (which needs JDK + Maven + the whole dependency tree)
# is confined to the first stage and discarded.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY . .
RUN mvn -B -ntp -pl service -am -DskipTests package

# ---- runtime stage ----
# RATIONALE: Eclipse Temurin 25 JRE; the app runs on Java 25 virtual threads.
FROM eclipse-temurin:25-jre
WORKDIR /app
# The repackaged bootable jar carries the 'exec' classifier (see service/pom.xml).
COPY --from=build /app/service/target/rate-limiter-service-*-exec.jar app.jar
EXPOSE 8080
# Liveness probe: the JVM is alive. (Readiness is a separate probe used by nginx
# / Kubernetes to stop routing traffic.)
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO /dev/null http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
