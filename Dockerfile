# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apt-get update -q && apt-get install -y -q maven && \
    mvn dependency:go-offline -q && \
    mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Non-root user for security
RUN groupadd -r wsa && useradd -r -g wsa wsa
USER wsa

WORKDIR /app
COPY --from=builder /build/target/mini-wsa-*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-postgres}", \
  "-jar", "app.jar"]
