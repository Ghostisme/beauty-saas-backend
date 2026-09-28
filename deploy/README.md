# 余乐圈美业 SaaS —— VPS 部署手册

两条 Jenkins 流水线,GitHub webhook 自动触发。域名 `beauty.darkrich.com`。

> 当前这个子域名还没有自己的 nginx `server_name`，外部 HTTPS 请求会落到现有
> Agent Studio 的默认站点。安装本项目的 `20-beauty.conf` 后，`beauty.darkrich.com`
> 会**有意切换**为本项目；`agent.darkrich.com` 等原有子域名不改。

**这是临时演示部署,用完后整体移除(见 §8)。** 但流水线的结构是可迁移的:
换服务器时改的只有 §3 那几个一次性步骤,流水线本身不用动。

## 1. 最终形态

前后端完全拆开,各自独立的 job、独立的构建历史、独立的成功/失败状态、
独立的回滚粒度。

| Jenkins Job | 仓库 | **Script Path** | 产物 | 环境变量文件 |
|---|---|---|---|---|
| `beauty-api` | beauty-saas-backend | `deploy/Jenkinsfile` | 容器 ×2(backend + mysql) | `/opt/beauty-saas/server.env` |
| `beauty-web` | beauty-saas-frontend | `deploy/Jenkinsfile` | 容器 | `/opt/beauty-saas/web.env` |

两条都用 Jenkins 的**「流水线」**类型,只构建 `main`。构建哪个分支由 job 配置里的
Branch Specifier 决定,Jenkinsfile 里不做分支判断。

### 1.1 前后端的环境变量是分开的

这是拆流水线顺带拿到的安全收益:

```
/opt/beauty-saas/
├── web.env      ← 只有域名,没有任何密钥
└── server.env   ← JWT 密钥、开通密钥、MySQL 密码
```

前端那条流水线只读 `web.env`,整条链路上根本不存在密钥。

### 1.2 请求路径

前端、后端、数据库各一个容器,各占一个独立的回环端口;公网仍只走标准 80 / 443:

```
浏览器 ──443──▶ 宿主机 nginx ──┬──▶ 127.0.0.1:19180  beauty-saas-frontend
                  TLS / HSTS    └──▶ 127.0.0.1:19190  beauty-saas-backend
                                            │
                                            ▼  内部网络走容器名 mysql:3306
                                     beauty-saas-mysql
                                     127.0.0.1:19306  (仅供运维连接)
```

前后端**同源**:前端在 `/`,后端在 `/api`。浏览器发的是同源请求,完全没有 CORS 预检。

三个端口都只绑 `127.0.0.1`,公网扫不到,**不需要在防火墙放行**。

### 1.3 端口分配

避开 portfolio 栈已占的 3010/3020/3021/3031/**3306**/5432/5678/6379/8010/9008,
也避开 8080/8443 这类会被扫描器优先探测的号:

| 宿主端口 | 容器内 | 容器 |
|---|---|---|
| 19180 | 8080 | `beauty-saas-frontend` |
| 19190 | 8080 | `beauty-saas-backend` |
| 19306 | 3306 | `beauty-saas-mysql` |

> **容器内部端口不需要避让。** 每个容器有独立的网络命名空间,等于各自一台主机。
> 你 VPS 上 `agent-studio-web` 和 `flowpilot-web` 容器内都监听 3000,已经并存
> 很久了。本项目前端和后端容器内也都是 8080,同理不冲突。
> 真正会冲突的只有宿主端口那一侧。

## 2. 边界:只动自己的东西

VPS 上已经跑着 portfolio 栈(agent-studio / flowpilot / lumax / n8n / uptime-kuma
和三个数据库)和六条流水线。本项目的资源全部带 `beauty` 前缀,与那些**完全不相交**:

| 类型 | 本项目的资源 |
|---|---|
| compose 项目 | `beauty-api`、`beauty-web` |
| 容器 | `beauty-saas-frontend`、`beauty-saas-backend`、`beauty-saas-mysql` |
| 数据卷 | `beauty-saas-mysql-data` |
| 网络 | `beauty-saas-net`(不接 `portfolio-net`) |
| 宿主端口 | `127.0.0.1:19180` / `19190` / `19306` |
| 目录 | `/opt/beauty-saas` |
| nginx | `/etc/nginx/conf.d/20-beauty.conf`(已有站点都是 `10-*.conf`) |
| Jenkins job | `beauty-api`、`beauty-web` |

**只读复用**(不修改任何已有文件):`/etc/nginx/snippets/portfolio-*.conf` 三个公共片段、
已有的 `portfolio-autoheal`(靠 `autoheal=true` 标签自动接管本项目容器)、Jenkins 本身。
证书不复用主站的 `/etc/letsencrypt/live/darkrich.com/`：本项目使用独立的
`/etc/letsencrypt/live/beauty.darkrich.com/` lineage，便于临时下架时安全删除。

数据库是**本项目独占的实例**,不是共用 `portfolio-mysql`。刻意不复用 —— 复用要往一台
跑着 `agent_studio` 的活实例里建库建号,移除时还得反向 DROP,风险不对称。独立实例的
数据全在一个卷里,删栈即删干净。代价是多约 200MB 内存。

### ⚠️ 与参考架构那六条流水线的一个重要区别

那六条的 `post` 块里有这两行全局命令:

```groovy
docker image prune -f
docker builder prune -f --filter until=168h
```

它们会波及 VPS 上其它项目的镜像和构建缓存(之前误删过 12.89GB 缓存和别的项目的
数据卷)。**本项目这两条流水线不使用它们**,改成只按镜像名删自己的旧 tag,保留最近
3 个。teardown 脚本同理,全部按显式清单精确删除。

## 3. 服务器一次性准备

以下全部是一次性的。跑完之后日常只有 Jenkins 在动。

### 3.1 建目录和 Docker 网络

```bash
mkdir -p /opt/beauty-saas
chmod 700 /opt/beauty-saas
docker network create --driver bridge beauty-saas-net
```

`700` 是为了保护 `server.env` 里的密钥。网络声明成 external 是因为前后端两栈都要
接入它,谁先起都不该依赖对方(流水线的环境自检阶段也会兜底创建)。

### 3.2 写两份 env

**先改前两行**,密码别用引号和 `#`。四个密钥自动随机生成:

```bash
ADMIN_PW='CHANGE_ME_设置一个强密码'
cat > /opt/beauty-saas/server.env <<EOF
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 32)
MYSQL_PASSWORD=$(openssl rand -hex 32)
JWT_SECRET=$(openssl rand -hex 32)
PLATFORM_PROVISIONING_KEY=$(openssl rand -hex 32)
PLATFORM_ADMIN_INITIAL_PASSWORD=$ADMIN_PW
CORS_ALLOWED_ORIGINS=https://beauty.darkrich.com
EOF
chmod 600 /opt/beauty-saas/server.env
```

前端那份没有密钥,权限可以宽一些:

```bash
echo 'BEAUTY_SITE_DOMAIN=beauty.darkrich.com' > /opt/beauty-saas/web.env
chmod 640 /opt/beauty-saas/web.env
```

忘改 `ADMIN_PW` 的话流水线的环境自检阶段会检测到 `CHANGE_ME` 并直接失败。

### 3.3 给 Jenkins 授权

> **复用 VPS 上现有的 Jenkins**，不新建 Jenkins 容器或第二个 systemd 服务。
> 参考当前服务器的 host Jenkins 形态：systemd 管理，工作区通常在
> `/var/lib/jenkins/workspace`。先用 `systemctl status jenkins` 和
> `ss -lntp | grep 9000`确认实际监听地址；beauty 部署不修改它的端口和现有 job。
> 宿主机 Jenkins 用 `usermod` / `setfacl` 授权，不涉及容器挂载。

`jenkins` 用户应该已经在 docker 组里(六条流水线都在用),确认一下:

```bash
id jenkins | tr ',' '\n' | grep -q docker && echo "已在 docker 组 ✓" || echo "需要加组"
```

需要加组的话(加完**必须重启**才生效,光重新登录不够):

```bash
usermod -aG docker jenkins && systemctl restart jenkins
```

放行本项目的 env 读权限:

```bash
setfacl -m u:jenkins:rx /opt/beauty-saas
setfacl -m u:jenkins:r  /opt/beauty-saas/server.env /opt/beauty-saas/web.env
```

`/opt/beauty-saas` 是 700 root,用 ACL 精确放行 —— 密码文件的所有权留在 root,
jenkins 只拿到读权限。

验证两条都要成功:

```bash
sudo -u jenkins docker ps >/dev/null && echo "docker 可用 ✓"
sudo -u jenkins head -1 /opt/beauty-saas/server.env >/dev/null && echo "env 可读 ✓"
```

### 3.4 插件

`系统管理 → 插件管理 → Available plugins`:

| 插件 | 为什么需要 |
|---|---|
| **Pipeline** | 流水线基础(通常已装) |
| **Git** | 从 SCM 拉取 Jenkinsfile(通常已装) |
| **GitHub** | 提供 webhook 触发器和 `githubPush()` |
| **Timestamper** | Jenkinsfile 用了 `timestamps()`,**不装会直接报错** |
| **Pipeline: Stage View** | 可视化各阶段耗时 |

已经在用那六条流水线的话,这些应该都装好了。

## 4. 建两条流水线

### 4.1 GitHub 凭据

仓库是私有的话必须加。已有 `github-ghostisme` 凭据就直接复用,跳过这步。

1. GitHub → `Settings → Developer settings → Personal access tokens → Tokens (classic)`
2. 勾 `repo` 和 `admin:repo_hook`(后者让 Jenkins 能自动注册 webhook)
3. Jenkins → `凭据 → 系统 → 全局凭据 → Add Credentials`
   - Kind: `Username with password`
   - Username: `Ghostisme`
   - Password: 刚生成的 token
   - ID: `github-ghostisme`

**不需要在 Jenkins 里配任何应用密钥。** 所有密钥都在 `/opt/beauty-saas/server.env`,
由 `docker compose --env-file` 读取 —— 既不进构建日志,也不进镜像层。

### 4.2 建 job

`新建任务` → 名称 `beauty-api` → 选 **流水线** → 确定。进配置页后只动两块:

**① Build Triggers** — 勾上 `GitHub hook trigger for GITScm polling`

这是 webhook 生效的关键。Jenkinsfile 里虽然也写了 `triggers { githubPush() }`,
但那要等 job 至少成功跑过一次才会被注册,所以第一次得在这里手工勾。

**② Pipeline**

| 字段 | 值 |
|---|---|
| Definition | `Pipeline script from SCM` |
| SCM | `Git` |
| Repository URL | `https://github.com/Ghostisme/beauty-saas-backend.git` |
| Credentials | `github-ghostisme` |
| Branch Specifier | `*/main` |
| **Script Path** | `deploy/Jenkinsfile` |

保存。再用页面最下方的 `Copy from` 填 `beauty-api` 建第二个,只改两个字段:

| Item 名称 | Repository URL | Script Path |
|---|---|---|
| `beauty-api` | `.../beauty-saas-backend.git` | `deploy/Jenkinsfile` |
| `beauty-web` | `.../beauty-saas-frontend.git` | `deploy/Jenkinsfile` |

### 4.3 Webhook

§4.1 的 token 勾了 `admin:repo_hook` 的话 Jenkins 会自动注册,跳过这步。

手动配:两个仓库各配一个 → `Settings → Webhooks → Add webhook`

| 字段 | 值 |
|---|---|
| Payload URL | **现有 Jenkins 对外 URL** + `/github-webhook/`（不要填 `beauty.darkrich.com`） |
| Content type | `application/json` |
| Which events | `Just the push event` |

末尾斜杠不能少。若当前 Jenkins 仍以 `http://159.195.232.67:9000` 对外提供服务，
先沿用现有 webhook 地址；不要为了 beauty 改动 Jenkins 监听。配完在 `Recent Deliveries`
里能看到一条测试请求,响应应是 `200`。

## 5. 首次部署

### 5.1 推代码

Jenkins 要先能拉到 Jenkinsfile:

```bash
cd "d:\github_code\余乐圈美业项目资料\beauty-saas-backend"
# 只提交本次部署需要的文件；不要用 git add -A 把工作区其它脚本/改动带进去
git add deploy .dockerignore
git diff --cached --stat
git commit -m "feat: add beauty VPS deployment pipeline"
git push origin main
```

```bash
cd "d:\github_code\余乐圈美业项目资料\beauty-saas-frontend"
# 同样只提交部署文件，先检查暂存清单
git add deploy .dockerignore
git diff --cached --stat
git commit -m "feat: add beauty web deployment pipeline"
git push origin main
```

### 5.2 部署顺序

job 建完**不会**自动开始构建,首次要自己点 `立即构建`。**先后端再前端**,
别让两条同时抢 CPU:

1. `beauty-api` → 首次要等 MySQL 初始化 + Flyway 跑 V1~V5 迁移,可能两三分钟
2. `beauty-web` → 快很多

每条成功一次之后,Jenkinsfile 里声明的 `triggers { githubPush() }` 才会被注册,
往后推代码就自动触发了。

### 5.3 DNS(必须在签证书之前)

```bash
dig +short beauty.darkrich.com
```

必须返回 `159.195.232.67`。

> ⚠️ 这一步不能跳。这台 VPS 的证书走 **webroot(HTTP-01)** 验证,
> Let's Encrypt 要真的访问 `http://beauty.darkrich.com/` 才能签发 ——
> 解析没生效就必然失败,还白耗一次失败配额。
>
> 这和参考架构 README 里"DNS-01 不需要 A 记录生效也能签"的说法不同,
> 那是针对泛域名 DNS-01 方案写的,而这台机器实际用的是 HTTP-01。
>
> 域名当前使用 netcup CloudDNS。没有泛域名 `* A` 记录的话,要单独加一条
> `beauty A 159.195.232.67`；本次 HTTP-01 只要求这个具体记录能访问到 VPS。

### 5.4 证书与 nginx(顺序不能颠倒)

> **不要把 beauty 加进主站证书。** 这台 VPS 当前的
> `/etc/letsencrypt/live/darkrich.com/` 是 portfolio 正式站点共用的显式
> SAN 证书，不是 wildcard；而 `darkrich.com` 的 DNS 在 netcup CloudDNS，
> `certbot-dns-netcup` 的 Legacy API 看不到这个 zone。实际可用的是
> HTTP-01 + webroot `/var/www/acme`:
>
> 具体 SAN 以服务器上 `certbot certificates` 的实际输出为准；已确认
> `beauty.darkrich.com` 不在主站 lineage 中。当前续期方式是
> `authenticator = webroot`（HTTP-01，不是 dns-netcup），且不依赖
> `/etc/letsencrypt/netcup.ini`。
>
> beauty 是临时项目，必须单独签
> `/etc/letsencrypt/live/beauty.darkrich.com/`。不要运行 `certbot --nginx`，
> 也不要对 `darkrich.com` 使用 `--expand`：临时域名下架后，如果它和主站共用
> 一个 lineage，主站续期也可能被拖失败。

先核对当前状态,后面的命令要照它的输出填:

```bash
certbot certificates
```

#### ① 装引导配置(只有 80 端口)

这一步解决鸡生蛋:webroot 验证需要 80 端口先能取到 challenge 文件,
而完整版配置里的 443 block 引用的证书此刻还不含 `beauty`。

```bash
cp /var/lib/jenkins/workspace/beauty-api/deploy/nginx/20-beauty-bootstrap.conf /etc/nginx/conf.d/
sed -i 's/\r$//' /etc/nginx/conf.d/20-beauty-bootstrap.conf
nginx -t && systemctl reload nginx
```

引导配置刻意不含 443、也不发 HSTS —— 证书还没覆盖本站时发 HSTS
会把浏览器锁死在 HTTPS,反而让站点彻底打不开。

#### ② 验证 challenge 路径通了

**这步不过就别往下走**,否则会白耗一次签发配额:

```bash
mkdir -p /var/www/acme/.well-known/acme-challenge
echo ok > /var/www/acme/.well-known/acme-challenge/probe
curl -s http://beauty.darkrich.com/.well-known/acme-challenge/probe
rm -f /var/www/acme/.well-known/acme-challenge/probe
```

必须输出 `ok`。输出别的内容说明请求被 301 吃掉或落到了别的 server 块。

#### ③ 签 beauty 独立证书

```bash
certbot certonly --cert-name beauty.darkrich.com \
  --webroot -w /var/www/acme \
  --email YOUR_EMAIL --agree-tos --no-eff-email --non-interactive \
  --deploy-hook "systemctl reload nginx" \
  -d beauty.darkrich.com
```

看到 `Successfully received certificate` 后，确认它是独立目录:

```bash
openssl x509 -in /etc/letsencrypt/live/beauty.darkrich.com/cert.pem \
  -noout -subject -dates -ext subjectAltName
```

输出应包含 `beauty.darkrich.com`，且主站证书的 SAN 列表与路径不应发生变化。

> 如果看到 `too many failed authorizations`，不要重复试错，等待 Let’s Encrypt
> 的 hostname 失败窗口恢复后再试；先排查上一步 challenge curl 是否确实输出 `ok`。

#### ④ 换成完整配置

```bash
rm -f /etc/nginx/conf.d/20-beauty-bootstrap.conf
cp /var/lib/jenkins/workspace/beauty-api/deploy/nginx/20-beauty.conf /etc/nginx/conf.d/
sed -i 's/\r$//' /etc/nginx/conf.d/20-beauty.conf
```

域名已写死在配置里,不用 sed 替换域名。**先 `nginx -t` 再 reload** ——
配置有错时 reload 会让 nginx 整体拒绝加载,把已有六个站点一起带挂:

```bash
nginx -t && systemctl reload nginx
```

#### ⑤ 验证续期链路

`beauty` 走 HTTP-01,续期时 Let's Encrypt 会再来取 challenge 文件,
所以 `20-beauty.conf` 里那段 `location /.well-known/acme-challenge/`
**必须一直留着** —— 这是以后改配置时最容易被顺手删掉的东西。

```bash
certbot renew --dry-run
```

现有的所有 lineage 加上新建的 `beauty.darkrich.com` 都应显示成功。
已有的 cron(`17 3 * * *`)会一并管本站证书,不需要额外配置。

> **配额提醒**: beauty 是独立证书，不会改主站 lineage；但同一 hostname 的
> **验证失败**有每小时限制，必须先把 challenge 路径测通再签。

## 6. 验收

```bash
curl -I https://beauty.darkrich.com/
curl -i https://beauty.darkrich.com/api/user/login
```

- [ ] 浏览器打开 `https://beauty.darkrich.com/` 显示登录页
- [ ] 用 `ADMIN_PW` 登录平台 `admin` 成功
- [ ] 刷新任意子路由不是 404(SPA fallback 生效)
- [ ] DevTools 里 API 打到 `beauty.darkrich.com/api/...`,同源无 CORS 预检
- [ ] API 探活返回 **405**(该路径只接受 POST,说明路由正常)
- [ ] 三个容器都 `healthy`:`docker ps --filter name=beauty-saas`
- [ ] **已有站点未受影响**:

```bash
for d in agent flow n8n lumax uptime demo; do printf '%-9s %s\n' "$d" "$(curl -s -o /dev/null -w '%{http_code}' https://$d.darkrich.com/)"; done
```

- [ ] `beauty` 使用独立证书 lineage，主站证书未被改写:

```bash
openssl x509 -in /etc/letsencrypt/live/beauty.darkrich.com/cert.pem \
  -noout -subject -dates -ext subjectAltName
openssl x509 -in /etc/letsencrypt/live/darkrich.com/cert.pem \
  -noout -subject -dates -ext subjectAltName
```

- [ ] **其它项目容器未受影响**:`docker ps --filter name=portfolio-` 仍是原来那几个

登录成功后建议把 `server.env` 里的 `PLATFORM_ADMIN_INITIAL_PASSWORD` 清空,
再跑一次 `beauty-api` 勾 `REDEPLOY`(它不会重置已存在的账号,留着只是多一份明文密码)。

## 7. 日常运维

### 改代码

推到 `main` 即自动构建部署。一次 push 只会唤醒对应仓库的那条流水线 ——
前后端是两个仓库,天然隔离。

### 手动参数

流水线页面 `Build with Parameters`:

| 参数 | 什么时候用 |
|---|---|
| `REDEPLOY` | **改了 `server.env` 后用这个**。不重新打包,只用当前镜像重新 up 一次让容器加载新配置,几秒完事 |
| `FORCE_REBUILD` | 忽略变更检测强制重新打包。本项目前端**没有构建期变量**,改域名不需要勾这个 |
| `NO_CACHE` | 怀疑缓存脏了。正常的依赖变更**不需要**勾 |

> 这里和参考架构那几个项目有个区别:lumax / agent-studio 的前端用了
> `VITE_*` / `NEXT_PUBLIC_*`,那些是**构建期**内联进 JS bundle 的,改域名必须
> `FORCE_REBUILD`。本项目前端把 API 地址写死成同源 `/api`,所以换域名只改 nginx 就行。

### 常用命令

| 场景 | 命令 |
|---|---|
| 看状态 | `docker ps --filter name=beauty-saas` |
| 看后端日志 | `docker logs -f beauty-saas-backend` |
| 看前端日志 | `docker logs -f beauty-saas-frontend` |
| 只重启某个容器 | `docker restart beauty-saas-backend` |
| 改 nginx 配置 | 直接改 `/etc/nginx/conf.d/20-beauty.conf`,然后 `nginx -t && systemctl reload nginx` |

注意**重启不等于重新部署** —— 用的还是原来那个镜像。

连数据库:

```bash
docker exec -it beauty-saas-mysql mysql -uroot -p"$(grep '^MYSQL_ROOT_PASSWORD=' /opt/beauty-saas/server.env | cut -d= -f2-)" beauty_saas
```

备份:

```bash
docker exec beauty-saas-mysql mysqldump -uroot -p"$(grep '^MYSQL_ROOT_PASSWORD=' /opt/beauty-saas/server.env | cut -d= -f2-)" --single-transaction beauty_saas | gzip > /root/beauty_saas-$(date +%Y%m%d-%H%M%S).sql.gz
```

### 回滚

健康检查失败时流水线会**自动回滚**到上一版镜像。手动回滚:

```bash
docker images beauty-saas-backend --format '{{.Tag}}'
```

挑一个 tag,然后在 Jenkins 里用那个 commit 重新构建;或直接:

```bash
docker tag beauty-saas-backend:<那个tag> beauty-saas-backend:rollback
docker restart beauty-saas-backend
```

## 8. 移除(演示结束后)

```bash
/opt/beauty-saas/teardown.sh --dry-run   # 先看清单,不动手
```

确认无误后执行,需手工输入 `REMOVE`:

```bash
/opt/beauty-saas/teardown.sh
```

脚本不在 `/opt/beauty-saas` 里的话(它随仓库走),从 Jenkins 工作区取:

```bash
bash /var/lib/jenkins/workspace/beauty-api/deploy/teardown.sh --dry-run
```

按显式清单删:两个 compose 项目、三个容器、一个卷、一个网络、两个镜像仓库的全部 tag、
一份 nginx 配置、`/opt/beauty-saas` 目录。删完会打印残留核对和 `portfolio-*` 状态
供你确认没被波及。

**刻意不删**:基础镜像(`mysql:8.0`、`maven`、`temurin`、`node`、`nginx:alpine`,
可能被其它项目共用)、主站 `darkrich.com` 证书、`/etc/nginx/snippets/` 公共片段、Jenkins 本身
及其它六条流水线。

两个 Jenkins job 需手工删(Jenkins 的数据在容器卷里):
`Jenkins → beauty-api / beauty-web → 左侧「删除流水线」`。

本项目的独立证书 lineage 也要单独删:

```bash
certbot delete --cert-name beauty.darkrich.com
```

DNS 里单独加过的 `beauty` 记录需手工去面板删。

## 9. 排查

### 流水线失败在「环境自检」

报错信息会直接指出原因:`server.env` 不存在、有 `CHANGE_ME` 残留、格式非
`KEY=VALUE`(会给出行号)、或带 CRLF 行尾。CRLF 那条的修法:

```bash
sed -i 's/\r$//' /opt/beauty-saas/server.env
```

> 为什么要拦这个:值尾部一个隐形 `\r` 会让数据库密码全部对不上,而报出来的错是
> 「认证失败」,极难联想到是行尾问题。

### 站点 502

先分清是哪一侧:

```bash
curl -I http://127.0.0.1:19180/ ; curl -i http://127.0.0.1:19190/api/user/login
```

```bash
docker ps -a --filter name=beauty-saas --format 'table {{.Names}}\t{{.Status}}'
ss -tlnp | grep -E ':(19180|19190)'
tail -50 /var/log/nginx/beauty.error.log
```

| 状态 | 含义 | 处理 |
|---|---|---|
| `Restarting (N)` 反复出现 | 启动就崩 | 看 `docker logs` |
| `Up N hours (unhealthy)` | 进程活着但不响应 | `portfolio-autoheal` 应在 30 秒内自动重启 |
| `Up N hours (healthy)` 但仍 502 | 容器没问题,是 nginx 侧 | 检查端口映射和 nginx 配置 |

### 容器反复重启且没有应用日志

大概率 JVM 被 OOMKill:

```bash
docker inspect beauty-saas-backend -f '{{.State.OOMKilled}}'
```

为 `true` 就把 `deploy/docker-compose.yml` 里的 `mem_limit` 从 `512m` 上调。
流水线的健康检查阶段失败时也会自动打印这一项。

### 首次启动卡在健康检查

Flyway 正在跑 V1~V5 迁移,看进度:

```bash
docker logs -f beauty-saas-backend
```

### 流水线每次都在跑全量构建

看决策表里的「比较基点」。一直显示"无(首次构建)"说明
`GIT_PREVIOUS_SUCCESSFUL_COMMIT` 取不到 —— 通常是这条 job 从来没成功过,
或构建历史被 `buildDiscarder` 清掉了。先让它成功一次。

### 前端容器起来了但站点 404

构建阶段出问题时容器照样能起来。流水线的健康检查会检测 `index.html` 是否存在,
也可手工:

```bash
docker exec beauty-saas-frontend ls -la /usr/share/nginx/html/
```

### 浏览器强制跳 HTTPS 且无法绕过

已有站点发的是带 `includeSubDomains` 的一年期 HSTS,你的浏览器此前在裸域名上接收过。
证书必须先就位,别指望 HTTP 裸测;真撞上了用无痕窗口,或在
`chrome://net-internals/#hsts` 删掉该域策略。

### ⚠️ 一个仍未修复的既有隐患:443 缺 default_server

这个和本项目无关,但会影响你对验收结果的判断,所以记在这里。

**现状**:`/etc/nginx/conf.d/` 里没有 443 的 `default_server`。于是
`https://darkrich.com`(裸域名)会落到**配置解析顺序里第一个** 443 server 块 ——
按文件名排序就是 `10-agent-studio.conf`。

**后果**:裸域名在证书 SAN 里,握手成功,浏览器不会有任何提示,于是正常接收了
agent-studio 那个块发出的:

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
```

这个头一旦以**裸域名**为宿主被接收,浏览器会把 `*.darkrich.com` 下**所有**
子域名锁成 HTTPS-only,为期一年,且**不允许用户点"继续访问"绕过**。

之前 `demo.darkrich.com` 打不开,根因就是这个 —— 不是 demo 自己的配置问题。
所以如果 `beauty.darkrich.com` 在证书扩展完成前你访问过,可能会看到
`ERR_SSL_*` 且无法绕过;用无痕窗口,或去 `chrome://net-internals/#hsts`
删掉该域的策略。

**修法**(参考架构里已有现成文件 `01-default-ssl.conf`,两分钟的事):

```bash
cp /root/portfolio-nginx/sites/01-default-ssl.conf /etc/nginx/conf.d/
sed -i 's/\r$//;s/PORTFOLIO_DOMAIN/darkrich.com/g' /etc/nginx/conf.d/01-default-ssl.conf
nginx -t && systemctl reload nginx
```

那个文件刻意**不** include `portfolio-security-headers.conf` —— 兜底块绝不能发
HSTS,那正是要避免的东西。装完后未匹配的请求一律 444 断开。

验证裸域名不再落到 agent-studio:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://darkrich.com/
```

应该是 `000`(连接被断开),而不是 `200`。

### 本机 SSH 连不上 VPS

若报 `Connection timed out during banner exchange`,是**本机代理在 TUN 模式下劫持了
这个 IP**:TCP 握手在本地被伪造,SSH 永远等不到服务端 banner。自检(连一个必然不
存在的端口,若也显示 OPEN 就证实了):

```bash
(echo > /dev/tcp/159.195.232.67/54321) 2>/dev/null && echo "被代理劫持" || echo "正常"
```

处理:把该 IP 加进代理客户端的直连 / 绕过规则,或临时关掉 TUN 模式。
`ping` 返回 `TTL=64` 且 `<1ms` 也是同一症状的旁证。

改用 Jenkins 之后,日常部署走 GitHub webhook,不需要 SSH —— 这个问题只影响
§3 的一次性准备和排查时登服务器。
