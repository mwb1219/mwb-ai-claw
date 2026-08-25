# ============================================================
# example-commerce-frontend 镜像：多阶段构建
#   阶段一：Node 构建（npm ci → vite build 产出 dist/）
#   阶段二：Nginx 静态托管（dist）+ 反向代理 API 到后端 example-commerce:8080
#
# 构建命令（推荐走 docker compose，context 指向本目录）：
#   docker compose -f ../example-commerce/docker-compose.yml up -d --build
# 或单独构建：
#   docker build -t example-commerce-frontend .
# ============================================================

# ---------- 阶段一：构建（React 18 + Vite 5，Node 20 LTS） ----------
FROM node:20-alpine AS builder
WORKDIR /app

# 先装依赖（package-lock.json 保证可复现，npm ci 可命中层缓存）
# 国内网络访问 registry.npmjs.org 不稳定，改用 npmmirror 镜像加速；--mount=type=cache 缓存 npm 下载
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm config set registry https://registry.npmmirror.com && \
    npm ci

# 拷贝源码并构建（tsc --noEmit && vite build → dist/）
COPY . .
RUN --mount=type=cache,target=/root/.npm npm run build

# ---------- 阶段二：Nginx 运行 ----------
FROM nginx:1.27-alpine
# 静态托管 + API 反向代理配置（见 nginx.conf / proxy-sse.conf）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY proxy-sse.conf /etc/nginx/proxy-sse.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
