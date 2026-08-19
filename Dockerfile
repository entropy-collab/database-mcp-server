# syntax=docker/dockerfile:1
# 支持多平台构建：amd64 和 arm64
# 构建命令：
#   docker buildx build --platform linux/amd64,linux/arm64 -t database-mcp-server:latest --push .
#   或本地测试：docker buildx build --platform linux/amd64 -t database-mcp-server:latest .

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml first to leverage Docker layer cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM --platform=$TARGETPLATFORM eclipse-temurin:17-jre

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/database-mcp-server-0.1.0-SNAPSHOT.jar app.jar

# Create non-root user with writable temp directory
RUN groupadd -r spring && useradd -r -g spring spring \
    && mkdir -p /tmp && chown -R spring:spring /tmp /app

USER spring:spring

EXPOSE 8686

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8686/actuator/health || exit 1

# Environment variables (no hardcoded passwords)
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=production

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
