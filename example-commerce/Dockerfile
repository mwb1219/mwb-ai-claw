# ============================================================
# example-commerce 应用镜像：多阶段构建（example-commerce 为独立工程，不随 mwb-ai-claw 仓库 reactor 构建，
# 独立管理版本号；构建上下文 = 本目录）
#
# 框架依赖 mwb-ai-claw-spring-boot-starter:1.0.3-SNAPSHOT 为本地 SNAPSHOT：
#   docker compose 通过 additional_contexts(host-m2) 将宿主机 ~/.m2 中的框架制品注入容器本地仓库，
#   无需在容器内联编框架（宿主机需先在仓库根目录执行 mvn install 保证 ~/.m2 中的 SNAPSHOT 最新）。
#
# 本示例零中间件依赖：H2 内存库（agent.storage.type 默认 file）+ RAG local 检索，
# docker compose 仅需应用 + 前端两个服务（见 docker-compose.yml）。
#
# 构建命令（推荐走 docker compose，context 已指向本目录）：
#   docker compose up -d --build
# 或单独构建：
#   docker build -t example-commerce .
# ============================================================

# ---------- 阶段一：Maven 构建（JDK 8，与 pom.xml java.version=1.8 对齐） ----------
FROM maven:3.9-eclipse-temurin-8 AS builder
WORKDIR /app

# 框架 SNAPSHOT 制品：从宿主机 ~/.m2（docker compose additional context: host-m2）拷入镜像层，
# 再在 RUN 中复制进容器本地仓库（--mount=type=cache 缓存的 /root/.m2），避免容器内联编框架；
# 宿主机需先在仓库根目录执行 mvn install 保证 ~/.m2 中的 SNAPSHOT 最新
COPY --from=host-m2 /repository/io/github/mwb1219 /framework-m2/io/github/mwb1219
COPY pom.xml ./
COPY src ./src
COPY .env ./

# 生成容器版 .env：本示例 .env 位于构建上下文根目录（src/main/resources 下仅有 .env.example 模板）。
# 其中若含 DB_URL/REDIS_URI 等 localhost 地址（本示例默认 H2/file 不需要），复制进镜像 /app 前
# 改写为 compose 服务名（mysql/redis），避免容器内连到自身端口失败；生成物置于 /app/.env.container，
# 不落 src/main/resources（避免被打进 jar / 影响 classpath 模板）
RUN sed -E \
        -e 's|^(DB_URL=jdbc:mysql://)localhost(:3306.*)|\1mysql\2|' \
        -e 's|^(REDIS_URI=redis://)localhost(:6379.*)|\1redis\2|' \
        .env > /app/.env.container

# 构建 example-commerce：Central 依赖由 Maven 自动下载并缓存（--mount=type=cache 避免重复下载）
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository/io/github && \
    cp -r /framework-m2/io/github/mwb1219 /root/.m2/repository/io/github/ && \
    mvn -B clean package -DskipTests

# ---------- 阶段二：运行镜像 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    CLAW_LOG_PATH=/app/logs
RUN mkdir -p /app/logs

# Spring Boot 可执行 jar（repackage 产物，example-commerce-<version>.jar）
COPY --from=builder /app/target/example-commerce-*.jar /app/app.jar

# 运行目录外部配置：ConfigFileLocator 优先读取 user.dir（=/app）下的同名文件，
# 将 .env（容器版）与 src/main/resources 下的 agents.json / orchestrations.json 复制到 /app，
# 实例启动后按「运行目录优先」加载；.env 经 DotenvEnvironmentPostProcessor 注入为最高优先级 PropertySource
COPY --from=builder /app/.env.container /app/.env
COPY --from=builder /app/src/main/resources/agents.json /app/agents.json
COPY --from=builder /app/src/main/resources/orchestrations.json /app/orchestrations.json

EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
