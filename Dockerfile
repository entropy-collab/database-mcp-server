# 构建前提：本地先执行 mvn clean install -DskipTests
# 构建命令：
#   docker buildx build --platform linux/amd64 -t database-mcp-server:latest .

# Use mirror for base image to improve pull reliability in CN environments
ARG BASE_IMAGE=docker.1ms.run/library/eclipse-temurin:25-jdk
FROM ${BASE_IMAGE}

# Install curl for health checks with Aliyun mirror
RUN sed -i 's/archive.ubuntu.com/mirrors.aliyun.com/g; s/security.ubuntu.com/mirrors.aliyun.com/g' /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy locally built jar (可执行 jar 由 database-mcp-app 模块产出)
COPY database-mcp-app/target/database-mcp-server-*.jar app.jar

# Debug: list jar contents
RUN unzip -l app.jar | grep "application-.*.yml" || echo "No application yml files found in jar"

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

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=production -jar app.jar"]
