# ============================================================
# example-web 应用镜像：多阶段构建（example-web 为独立工程，不随 mwb-ai-claw 仓库 reactor 构建，
# 独立管理版本号；构建上下文 = 本目录）
#
# 框架依赖 mwb-ai-claw-spring-boot-starter:1.0.3：Central 正式版，Maven 构建时自动下载；
# docker compose 仍通过 additional_contexts(host-m2) 可选注入宿主机 ~/.m2 的框架制品（本机构建加速，
# 缺失时 Maven 自动从 Central 拉取，无需在容器内联编框架）。
#
# 构建命令（推荐走 docker compose，context 已指向本目录）：
#   docker compose up -d --build
# 或单独构建：
#   docker build -t example-web .
# ============================================================

# ---------- 阶段一：Maven 构建（JDK 8，与 pom.xml java.version=1.8 对齐） ----------
FROM maven:3.9-eclipse-temurin-8 AS builder
WORKDIR /app

# 框架制品加速：从宿主机 ~/.m2（docker compose additional context: host-m2）拷入镜像层，
# 再在 RUN 中复制进容器本地仓库（--mount=type=cache 缓存的 /root/.m2），避免容器内重复下载
# Central 依赖（1.0.3 正式版；未注入时 Maven 自动从 Central 拉取）
COPY --from=host-m2 /repository/io/github/mwb1219 /framework-m2/io/github/mwb1219
COPY pom.xml ./
COPY src ./src

# 生成容器版 .env：src/main/resources/.env 中 DB_URL/REDIS_URI 为 localhost（本地开发地址），
# 复制进镜像 /app 前需改写为 compose 服务名（mysql/redis），否则容器内会连到自身 3306/6379 失败。
# 生成物置于 /app/.env.container（不在 src/main/resources 下，避免被打进 jar / 影响 classpath 模板）
RUN sed -E \
        -e 's|^(DB_URL=jdbc:mysql://)localhost(:3306.*)|\1mysql\2|' \
        -e 's|^(REDIS_URI=redis://)localhost(:6379.*)|\1redis\2|' \
        src/main/resources/.env > /app/.env.container

# 构建 example-web：Central 依赖由 Maven 自动下载并缓存（--mount=type=cache 避免重复下载）
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository/io/github && \
    cp -r /framework-m2/io/github/mwb1219 /root/.m2/repository/io/github/ && \
    mvn -B clean package -DskipTests

# ---------- Node 运行环境（供 MCP stdio 进程使用）：独立阶段提取二进制，避免 apt 源网络问题 ----------
FROM node:20-bookworm-slim AS node

# ---------- 阶段二：运行镜像 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    CLAW_LOG_PATH=/app/logs
RUN mkdir -p /app/logs

# 安装 Node.js（含 npm/npx）：mcp-server.json 中 tavily-mcp / amap-maps 以 stdio 方式由 npx 启动，
# 基础镜像 eclipse-temurin:8-jre 不含 Node。从 node:20-bookworm-slim 阶段复制二进制
# （同为 glibc 体系，可兼容运行；npm/npx 为符号链接，须整目录复制 /usr/local，
# 与 Temurin 的 /opt/java/openjdk 无冲突）；npm 默认 registry 切到 npmmirror，保证容器内 npx 拉取 MCP 包可达
COPY --from=node /usr/local/ /usr/local/
# 预装 mcp-server.json 引用的 MCP 包（全局安装，npx 启动时直接命中缓存，避免实例启动时联网下载超时；
# 版本须与 src/main/resources/mcp-server.json 保持一致）
RUN npm install -g tavily-mcp@0.1.2 @amap/amap-maps-mcp-server \
    && npm config set registry https://registry.npmmirror.com \
    && node -v && npm -v && npx -v

# Spring Boot 可执行 jar（repackage 产物，example-web-<version>.jar）
COPY --from=builder /app/target/example-web-*.jar /app/app.jar

# 运行目录外部配置：ConfigFileLocator 优先读取 user.dir（=/app）下的同名文件，
# 将 src/main/resources 下的 .env（容器版：DB_URL/REDIS_URI 已改写为 compose 服务名）与
# agents.json / orchestrations.json / mcp-server.json 复制到 /app，实例启动后按「运行目录优先」加载。
# 其中 .env 经 DotenvEnvironmentPostProcessor 注入为最高优先级的 PropertySource，
# 覆盖 compose env_file 注入的 localhost 默认值，保证容器内连接 mysql/redis 服务
COPY --from=builder /app/.env.container /app/.env
COPY --from=builder /app/src/main/resources/agents.json /app/agents.json
COPY --from=builder /app/src/main/resources/orchestrations.json /app/orchestrations.json
COPY --from=builder /app/src/main/resources/mcp-server.json /app/mcp-server.json

EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
