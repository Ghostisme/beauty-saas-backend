#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
#  余乐圈美业 SaaS —— VPS 移除脚本（在服务器上执行）
#
#  这个项目是临时演示，用完整体删掉。本脚本把「该删什么」写死成一份
#  显式清单，不做任何模糊匹配、不使用任何全局清理命令：
#
#      ❌ 绝不使用 docker system prune
#      ❌ 绝不使用 docker volume prune / network prune / image prune
#      ✅ 只按名字删下面清单里的资源
#
#  原因：全局清理命令会连带删掉 VPS 上其它项目（portfolio-* 等）的网络、
#  卷和构建缓存 —— 之前踩过这个坑，丢过别的项目的数据卷。
#
#  删除前会列出清单并要求手工确认，输入 REMOVE 才真的执行。
#
#  用法：
#    /opt/beauty-saas/teardown.sh          # 交互确认后移除
#    /opt/beauty-saas/teardown.sh --dry-run # 只打印将要删什么，不动手
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="/opt/beauty-saas"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# 两个 compose 项目：前后端各一条流水线、各一栈
PROJECTS=(beauty-web beauty-api)

# ── 移除清单（唯一的真相来源，改这里就够）─────────────────────────
CONTAINERS=(beauty-saas-frontend beauty-saas-backend beauty-saas-mysql)
VOLUMES=(beauty-saas-mysql-data)
NETWORKS=(beauty-saas-net)
# Jenkins 用「构建号-commit」给每次构建打 tag（例如 12-a1b2c3d），
# 还可能留下 latest / rollback。这里按**本项目的两个镜像仓库**清理所有 tag，
# 不写死一个不存在的 :deploy tag，也不碰任何其它仓库或基础镜像。
IMAGE_REPOS=(beauty-saas-frontend beauty-saas-backend)
NGINX_CONF="/etc/nginx/conf.d/20-beauty.conf"
PATHS=("$ROOT")

# Jenkins 里的两个 job。脚本不会自动删它们（Jenkins 的数据在容器卷里，
# 删 job 要走它的 Web UI 或 CLI），只在收尾时提示你手工删。
JENKINS_JOBS=(beauty-web beauty-api)

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
skip() { printf '\033[1;90m  – %s\033[0m\n' "$*"; }

# ── 打印清单 ────────────────────────────────────────────────────────
cat <<BANNER

═══════════════════════════════════════════════════════════════════
  将要移除的资源（且仅限这些）
═══════════════════════════════════════════════════════════════════
  compose : ${PROJECTS[*]}
  容器    : ${CONTAINERS[*]}
  数据卷  : ${VOLUMES[*]}          ← ⚠️ 业务数据会一并删除，不可恢复
  网络    : ${NETWORKS[*]}
  镜像仓库: ${IMAGE_REPOS[*]}（仅这些仓库的全部 tag）
  nginx   : $NGINX_CONF
  目录    : ${PATHS[*]}

  不会碰：portfolio-* 的任何容器 / 卷 / 网络、证书、其它站点配置、
          Jenkins 本身及其它六条流水线（本项目的两个 job 需手工删，收尾会提示）
═══════════════════════════════════════════════════════════════════
BANNER

if [[ $DRY_RUN -eq 1 ]]; then
  printf '\n\033[1;33m--dry-run：不执行任何删除操作\033[0m\n\n'
  exit 0
fi

printf '\n输入 \033[1;31mREMOVE\033[0m 确认移除（其它任何输入都会取消）: '
read -r answer
[[ "$answer" == "REMOVE" ]] || { printf '\n已取消，什么都没动。\n'; exit 0; }

# ── 1. 停掉并删除两个 compose 栈 ────────────────────────────────────
#  改用 Jenkins 之后 compose 文件在 Jenkins 工作区里（随时可能被清掉），
#  所以这里**不传 -f**：compose v2 支持只按项目名操作，靠容器上的
#  com.docker.compose.project 标签定位资源。
#
#  好处是不用读 compose 文件，也就不会因为 ${MYSQL_ROOT_PASSWORD:?}
#  这类必填变量在解析阶段校验失败而卡住（server.env 此时可能已经没了）。
#
#  这一步是「尽力而为」—— 真正的保证是下面按名字逐个清理的那几步。
log "停止并移除 compose 栈"
for p in "${PROJECTS[@]}"; do
  if docker compose -p "$p" down --volumes --remove-orphans 2>/dev/null; then
    ok "compose 项目 $p 已移除"
  else
    skip "compose 项目 $p 未能按项目名移除，下面按名字兜底"
  fi
done

# ── 2. 按名字兜底删容器 ─────────────────────────────────────────────
#  compose down 正常情况下已经删掉了，这一步只为处理
#  compose 文件丢失或曾手工 docker run 起过容器的情况
log "核对容器"
for c in "${CONTAINERS[@]}"; do
  if docker ps -aq -f "name=^${c}$" | grep -q .; then
    docker rm -f "$c" >/dev/null
    ok "已删除容器 $c"
  else
    skip "容器 $c 不存在"
  fi
done

# ── 3. 按名字删卷 ───────────────────────────────────────────────────
log "核对数据卷"
for v in "${VOLUMES[@]}"; do
  if docker volume ls -q -f "name=^${v}$" | grep -q .; then
    docker volume rm "$v" >/dev/null
    ok "已删除数据卷 $v"
  else
    skip "数据卷 $v 不存在"
  fi
done

# ── 4. 按名字删网络 ─────────────────────────────────────────────────
log "核对网络"
for n in "${NETWORKS[@]}"; do
  if docker network ls -q -f "name=^${n}$" | grep -q .; then
    docker network rm "$n" >/dev/null
    ok "已删除网络 $n"
  else
    skip "网络 $n 不存在"
  fi
done

# ── 5. 按仓库删本项目镜像 ──────────────────────────────────────────
#  只枚举上面的两个 beauty 仓库的 tag。基础镜像（mysql:8.0、maven、
#  temurin、node:22-alpine、nginx:alpine）刻意保留 —— 它们可能被其它
#  项目共用，而且重新拉取很慢。这里不使用 image prune。
log "核对镜像"
for repo in "${IMAGE_REPOS[@]}"; do
  found=0
  while IFS= read -r ref; do
    [[ -n "$ref" ]] || continue
    found=1
    if docker image inspect "$ref" >/dev/null 2>&1; then
      docker rmi "$ref" >/dev/null
      ok "已删除镜像 $ref"
    fi
  done < <(docker image ls "$repo" --format '{{.Repository}}:{{.Tag}}' | sort -u)
  [[ $found -eq 1 ]] || skip "镜像仓库 $repo 没有 tag"
done
skip "基础镜像（mysql / maven / temurin / node）保留，可能被其它项目共用"

# ── 6. 移除 nginx 站点配置 ──────────────────────────────────────────
log "移除 nginx 站点配置"
if [[ -f "$NGINX_CONF" ]]; then
  rm -f "$NGINX_CONF"
  ok "已删除 $NGINX_CONF"
  # 先 -t 再 reload：万一别的配置此刻是坏的，reload 会让 nginx 整体拒绝加载，
  # 把其它站点一起搞挂。测试不通过就只提示，不强行 reload。
  if nginx -t 2>/dev/null; then
    systemctl reload nginx
    ok "nginx 已重载（其它站点不受影响）"
  else
    printf '\033[1;33m  ! nginx -t 未通过，未执行 reload。请手工检查：nginx -t\033[0m\n'
  fi
else
  skip "$NGINX_CONF 不存在"
fi

# ── 7. 删目录 ───────────────────────────────────────────────────────
log "移除项目目录"
for p in "${PATHS[@]}"; do
  # 双重保险：只删 /opt/beauty-saas 这个确切路径，防止变量为空时误伤 /
  if [[ "$p" == "/opt/beauty-saas" && -d "$p" ]]; then
    rm -rf "$p"
    ok "已删除 $p"
  else
    skip "$p 不存在或不在允许删除的白名单内"
  fi
done

# ── 收尾核对 ────────────────────────────────────────────────────────
log "核对残留（应为空）"
docker ps -a --filter "name=beauty-saas" --format '  {{.Names}}\t{{.Status}}' || true
docker volume ls -q --filter "name=beauty-saas" | sed 's/^/  /' || true

log "确认其它项目未受影响"
docker ps --filter "name=portfolio-" --format '  {{.Names}}\t{{.Status}}' || true

log "Jenkins 流水线（需手工删，本脚本不动 Jenkins）"
for j in "${JENKINS_JOBS[@]}"; do
  printf '  – %s\n' "$j"
done
printf '\033[1;90m    Jenkins → 对应 job → 左侧「删除流水线」。\n'
printf '    其它六条流水线和 Jenkins 本身不受影响。\033[0m\n'

printf '\n'
ok "移除完成"
printf '  如果单独配过 DNS 记录 beauty.darkrich.com，记得去 DNS 面板删掉。\n'
printf '  泛域名证书其它站点还在用，不要动。\n'
printf '  若曾为本站单独签过证书：certbot delete --cert-name beauty.darkrich.com\n'
