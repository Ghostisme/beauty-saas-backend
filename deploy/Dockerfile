# syntax=docker/dockerfile:1
# ⚠️ 上面那行是 BuildKit 的 frontend 声明，不是普通注释，必须是文件第一行。
#    下面 RUN --mount=type=cache 依赖它 —— 缺了这行在部分 Docker 版本上会
#    报 "Unknown flag: mount" 而直接构建失败。
#
# 生产镜像（VPS 临时部署专用）
#
# 刻意和仓库根目录的 Dockerfile 并存、互不影响：那一份是本地开发在用的，
# 不要把两者合并。这一份多做了三件在 VPS 上必须做的事：
#
#   1. 装 curl 并声明 HEALTHCHECK —— VPS 上已有的 portfolio-autoheal 是靠
#      容器健康状态决定要不要重启的，没有 HEALTHCHECK 的容器它完全看不见。
#   2. 限制堆上限。JVM 默认按「宿主机」总内存推算堆大小，在设了 mem_limit
#      的容器里会算出一个超过限制的值，然后被内核 OOMKill —— 表现是容器
#      不留任何应用日志地反复重启，很难联想到是堆配置问题。
#   3. 非 root 运行。
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 依赖层单独成层：只改 src 时这一层整层命中缓存，不重新解析依赖树。
# cache mount 让已下载过的 jar 跨构建复用，改 pom.xml 时也只拉新增的那几个。
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

# curl 仅为下面的 HEALTHCHECK 服务，temurin 的 JRE 镜像不自带
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --shell /usr/sbin/nologin beauty

COPY --from=builder /app/target/*.jar app.jar
RUN chown beauty:beauty /app/app.jar
USER beauty

ENV TZ=Asia/Shanghai
EXPOSE 8080

# 健康检查要带 /api 前缀 —— application.yml 里设了 context-path: /api。
#
# 探的是 GET /api/user/login，而该路径只映射了 POST，所以预期拿到 405。
# 这里判定的本质是「Tomcat 和 DispatcherServlet 在正常路由请求」，
# 而不是某个业务接口返回成功 —— 所以把 2xx/4xx 一并算作健康，
# 只有 5xx 和连不上（curl 输出 000）才算不健康。
#
# 为什么不用 200 当判据：本项目没有引 actuator，不存在任何免鉴权的 200 端点。
# 为什么不加 curl -f：-f 会让 405 变成非零退出码，把健康的容器判成不健康。
#
# start-period 给到 90s：Spring 启动 + Flyway 首次跑 V1~V5 迁移需要时间。
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=6 \
  CMD curl -sS -o /dev/null -w '%{http_code}' \
      http://127.0.0.1:8080/api/user/login | grep -qE '^(200|401|403|405)$'

# SerialGC 而非默认的 G1：单核小容器里 G1 的并发线程和记忆集开销划不来，
# 这点数据量下 SerialGC 的暂停时间完全可接受，还能省几十 MB 常驻内存。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-jar", "app.jar"]
