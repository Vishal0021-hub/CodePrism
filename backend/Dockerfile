# ── Stage 1: Build JAR using Maven 3.9 + Temurin 21 ──────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache dependencies layer
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy source code and build production jar
COPY src/ ./src/
RUN mvn clean package -DskipTests -B

# ── Stage 2: Minimal JRE Runtime ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install curl for container health check and create non-root user
RUN apk add --no-cache curl \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy jar from builder stage with proper ownership
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar /app/app.jar

USER appuser

# JVM memory cap (512MB max heap), G1 collector, and crash on OOM for container restart
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Main application port (8081) and internal management actuator port (8082)
EXPOSE 8081 8082

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
