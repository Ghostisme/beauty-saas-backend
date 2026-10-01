#!/usr/bin/env bash
# 余乐圈美业 SaaS —— 现场重置平台超级管理员
#
# 仅操作本项目的 beauty_saas 数据库和 beauty-saas-mysql 容器。
# 不会访问、删除或修改其它项目的数据库、容器、卷或配置。
#
# 用法（在 VPS 上执行）：
#   bash reset-platform-admin.sh
#   bash reset-platform-admin.sh --generate
#
# 密码不会写入文件；--generate 只在终端输出一次随机密码。
# 由于应用仍兼容旧 MD5 密码，脚本先写入一次性 MD5；首次登录成功后
# IdentityService 会自动升级为 BCrypt。登录后请立即在右上角修改密码。
set -euo pipefail

ROOT="${BEAUTY_ROOT:-/opt/beauty-saas}"
ENV_FILE="${BEAUTY_ENV_FILE:-$ROOT/server.env}"
MYSQL_CONTAINER="${BEAUTY_MYSQL_CONTAINER:-beauty-saas-mysql}"
DB_NAME="beauty_saas"

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }

[[ -r "$ENV_FILE" ]] || die "找不到环境文件：$ENV_FILE"
command -v docker >/dev/null 2>&1 || die '找不到 docker'
command -v md5sum >/dev/null 2>&1 || die '找不到 md5sum'
command -v openssl >/dev/null 2>&1 || die '找不到 openssl'

# 只读取这一份项目环境文件，避免误接入其它数据库。
MYSQL_ROOT_PASSWORD="$(awk -F= '$1 == "MYSQL_ROOT_PASSWORD" { print substr($0, index($0, "=") + 1); exit }' "$ENV_FILE")"
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die "$ENV_FILE 中没有 MYSQL_ROOT_PASSWORD"

docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || die "找不到容器：$MYSQL_CONTAINER"
running="$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")"
[[ "$running" == 'true' ]] || die "容器未运行：$MYSQL_CONTAINER"

mysql() {
  # MYSQL_PWD 避免把数据库密码拼到 docker/mysql 命令行参数中。
  docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --protocol=socket -uroot --batch --skip-column-names "$DB_NAME" "$@"
}

mysql -e 'SELECT 1' >/dev/null || die '无法使用 server.env 中的 MYSQL_ROOT_PASSWORD 连接 beauty_saas'

if [[ "${1:-}" == '--generate' ]]; then
  NEW_PASSWORD="$(openssl rand -hex 12)"
  GENERATED=1
elif [[ -n "${1:-}" ]]; then
  die '不要把密码作为命令行参数传入；直接运行脚本后按提示输入，或使用 --generate'
else
  GENERATED=0
  read -r -s -p '输入新的平台 admin 密码（至少 8 位，回车后不回显）： ' NEW_PASSWORD
  printf '\n'
  read -r -s -p '再次输入新密码： ' CONFIRM_PASSWORD
  printf '\n'
  [[ "$NEW_PASSWORD" == "$CONFIRM_PASSWORD" ]] || die '两次密码不一致'
fi

[[ ${#NEW_PASSWORD} -ge 8 ]] || die '密码至少 8 个字符'
[[ ${#NEW_PASSWORD} -le 72 ]] || die '密码最多 72 个字符（应用 BCrypt 限制）'

PASSWORD_MD5="$(printf '%s' "$NEW_PASSWORD" | md5sum | awk '{print $1}')"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_FILE="${BEAUTY_BACKUP_DIR:-/root}/beauty_saas-before-platform-admin-reset-${STAMP}.sql.gz"

info "先备份 beauty_saas 到：$BACKUP_FILE"
docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
  mysqldump -uroot --single-transaction --routines --events "$DB_NAME" \
  | gzip > "$BACKUP_FILE"

[[ -s "$BACKUP_FILE" ]] || die "备份文件为空：$BACKUP_FILE"

# PASSWORD_MD5 只包含十六进制字符；SQL 中不拼接用户输入的原文密码。
mysql <<SQL
START TRANSACTION;
SET @platform_user_id := COALESCE((
  SELECT u.id
  FROM sys_user u
  WHERE u.tenant_id = 0 AND u.username = 'admin'
  ORDER BY u.id
  LIMIT 1
), 0);

INSERT INTO sys_user(tenant_id, username, password, nickname)
SELECT 0, 'admin', '$PASSWORD_MD5', '超级管理员'
WHERE @platform_user_id = 0;

SET @platform_user_id := IF(@platform_user_id = 0, LAST_INSERT_ID(), @platform_user_id);

INSERT IGNORE INTO sys_platform_admin(user_id) VALUES (@platform_user_id);
UPDATE sys_user
SET password = '$PASSWORD_MD5',
    tenant_id = 0,
    status = 1,
    deleted = 0,
    auth_version = auth_version + 1,
    update_time = CURRENT_TIMESTAMP
WHERE id = @platform_user_id;

INSERT INTO sys_platform_audit(platform_user_id, tenant_id, action, detail)
VALUES (@platform_user_id, 0, 'RESET_PLATFORM_ADMIN_PASSWORD', '运维脚本重置平台 admin 密码');
COMMIT;
SQL

mysql -e "SELECT u.id,u.username,u.tenant_id,u.status,u.deleted,IF(p.user_id IS NULL,0,1) AS platform_admin FROM sys_user u LEFT JOIN sys_platform_admin p ON p.user_id=u.id WHERE u.id=@platform_user_id OR (u.tenant_id=0 AND u.username='admin');" \
  >/dev/null

if [[ "$GENERATED" == 1 ]]; then
  printf '\n平台账号已就绪：\n  账号：admin\n  新密码：%s\n' "$NEW_PASSWORD"
else
  info '平台账号已重置：账号 admin'
fi
info "备份保留在：$BACKUP_FILE"
info '请用“平台登录”入口登录；首次成功登录后，立即在右上角“修改密码”。'
