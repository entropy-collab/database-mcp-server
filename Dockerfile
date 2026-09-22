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

# MCP Registry 用这个 label 校验镜像所有权：值必须与 server.json 的 name 逐字相同，
# 否则 mcp-publisher 拒绝发布。命名空间用 entropy-collab（仓库所在的 GitHub 账号），
# 因为 registry 的 GitHub 认证要求 name 前缀是 io.github.<账号名>/。
LABEL io.modelcontextprotocol.server.name="io.github.entropy-collab/database-mcp-server"

# Copy locally built jar (可执行 jar 由 database-mcp-app 模块产出)
COPY database-mcp-app/target/database-mcp-server-*.jar app.jar

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

# profile 只由 SPRING_PROFILES_ACTIVE 决定。这里刻意不再重复
# -Dspring.profiles.active=production：命令行 -D 的优先级高于环境变量，写死会让
# docker run -e SPRING_PROFILES_ACTIVE=... 与 compose 里的设置全部失效。
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
