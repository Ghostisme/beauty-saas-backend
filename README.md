# 余乐圈美业 SaaS 后端

Spring Boot **3.2.5** / Java 17 / MySQL 8 / Spring Security / JWT / Flyway。平台超级管理员与企业管理员分离：平台开通企业、创建企业管理员并管理全部企业；企业账号只能访问自己企业的用户、组织、房间、角色、授权、订单及短信管理数据。

## 账号与权限边界

- **本机原有 `admin` 是平台超级管理员，原密码保持不变。** 登录时选择“平台登录”，只填账号、密码，不填企业编码。它不再担任 `yulequan` 的企业负责人。
- 企业管理员由平台在“企业管理 → 开通企业”中创建，账号默认 `admin`，也可自定义；每家企业有自己的账号记录、密码与唯一租户 ID。企业登录需要企业编码、账号、密码。
- 平台“用户管理”的“全部企业”范围汇总查看所有企业的用户、部门 / 门店、房间、角色；包括停用企业。选择具体企业后可维护其数据，写入必须指定目标企业。
- 企业管理员不能开通企业、查看其他企业或通过请求头切换企业。平台身份由独立关联表确认，不凭用户名或前端角色字符串判断。
- 原 `yulequan` 企业及其业务数据保留；原负责人调整为平台账号后，该企业显示“待开通管理员”。平台可为它单独创建企业管理员，不复制平台账号或密码。

## 启动与升级

```powershell
cd "D:\github_code\余乐圈美业项目资料\beauty-saas-backend"
$env:DB_USERNAME = 'root'
# 根据本机 beauty_saas 数据库设置 DB_PASSWORD；不要改动其他业务数据库。
# 可选 DB_URL，默认 jdbc:mysql://localhost:3306/beauty_saas?...。
mvn spring-boot:run
```

新环境先执行 `src/main/resources/sql/init.sql` 创建 **beauty_saas** 库；已有库不需要重建。应用启动由 Flyway 按序执行尚未应用的迁移：V1 建立租户身份模型，V2 将原始平台管理员从企业身份中分离，V3 增加订单及核对设置。

**升级现有数据库前先备份，并确认 DB_URL 只指向 beauty_saas。** MySQL DDL 不能整体事务回滚；不要在其他业务库上运行本项目的迁移或初始化脚本。后续结构变更应增加 V4 等新迁移，不修改已经应用过的 V1、V2、V3。

- 旧 `sys_user` 保留 ID、密码、昵称及状态，补充 `tenant_id`、`auth_version`，改为企业内账号唯一。
- V1 将旧账号归入企业编码 **`yulequan`**；V2 只将租户 ID `1`、编码 `yulequan` 的原始负责人 `admin` 转为平台账号，不提升其他企业管理员的权限。
- V2 保留原账号 ID、密码和其他企业数据，撤销其旧企业负责人 / 部门 / 角色关联，并递增会话版本。平台账号内部使用保留值 `tenant_id=0`，**不创建“平台企业”租户记录**。
- 原密码保持不变；旧 MD5 密码在成功登录后升级为 BCrypt（超过 BCrypt 字节上限的遗留密码须通过改密接口更新）。V1 遇到有旧账号却没有有效 `admin` 的库会停止迁移并报错，不随机指定负责人。
- 新空库不内置通用密码。首次部署显式设置 `PLATFORM_ADMIN_INITIAL_PASSWORD`，初始化平台 `admin`；存在平台账号时此变量不会重置其密码。之后从平台页面开通企业并指定独立企业管理员密码。
- 原企业身份 JWT 及不含 `platformAdmin` 的旧浏览器缓存失效，需要重新登录。
- 旧管理员昵称乱码修复仍可使用 `src/main/resources/sql/repair-admin-nickname.sql`；只处理原始种子账号的已知乱码，不覆盖正常昵称。

### 密钥

| 环境变量 | 用途 |
| --- | --- |
| `JWT_SECRET` | 至少 32 字节的签名密钥。正式环境必须设置并妥善保存；不设置时仅使用本进程随机密钥，重启使全部登录失效 |
| `PLATFORM_PROVISIONING_KEY` | 可选的服务端自动开通密钥，至少 32 字节。为空或过短时只关闭密钥开通通道，不影响平台超管通过 JWT / 页面开通企业；不交给企业管理员或放进前端代码 |
| `PLATFORM_ADMIN_INITIAL_PASSWORD` | 仅在全新部署且尚无平台账号时初始化平台 `admin`；不重置已有账号。部署后可移除，不作为企业管理员默认密码 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据源配置；默认本地 beauty_saas，仅用于开发 |
| `CORS_ALLOWED_ORIGINS` | 逗号分隔的前端完整来源，默认允许 localhost / 127.0.0.1 的 5173、4173；手机 / PAD 局域网访问时追加 `http://电脑局域网IP:5173`，不使用通配符 |

为 JWT 签名和可选自动开通通道分别生成独立随机密钥，通过部署环境/密钥管理系统注入。Docker Compose 读取环境变量或未提交的 `.env`（模板 `.env.example`）。Maven 本地运行需要在同一终端设置 `$env:...`，不会自动读取 `.env`。

Docker 仍使用现有命名卷与 MySQL 容器定义。全新环境启动：

```powershell
docker compose up -d --build
docker compose logs -f backend
```

已有环境升级时，备份后只更新后端，**不停止或重建 MySQL**：

```powershell
docker compose build backend
docker compose up -d --no-deps backend
```

这会用新镜像重新创建后端容器，MySQL 容器与数据库命名卷保持不变。局域网 IP 改变后，更新 `.env` 的 `CORS_ALLOWED_ORIGINS` 并重新启动后端；Maven 运行时设置同名环境变量。

不要执行 `docker compose down -v` 或重置数据库卷。这里的 Compose 是本地开发配置，线上需另行配置数据库专用账号、TLS、备份、网关限流和密钥管理。

## 平台开通企业

**常规操作使用前端页面，不需要复制平台密钥：**

1. 选择“平台登录”，使用平台 `admin` 登录。
2. 侧栏“企业管理 → 开通企业”，填写企业名称、唯一企业编码、企业管理员账号 / 姓名 / 初始密码。
3. 将企业编码和新管理员凭据交付给企业负责人，负责人从“企业登录”进入。
4. 已有但缺少管理员的企业，使用该行的“开通管理员”；已有负责人可使用“重置管理员密码”。重置会撤销该企业管理员的旧登录。
5. 企业停用会撤销全部企业账号会话，重新启用也不会恢复旧会话；平台仍可读取历史数据。

可选的服务端自动开通脚本（配置 `PLATFORM_PROVISIONING_KEY` 后）：

```powershell
.\scripts\New-Enterprise.ps1 -Code 'company-a' -Name '示例美业企业' -AdminName '企业负责人'
```

脚本交互读取平台密钥（或使用当前终端的 `PLATFORM_PROVISIONING_KEY`）、管理员密码及确认密码，不打印密码、不写入文件。远程 API 只允许 HTTPS，可用 `-ApiBase 'https://example.com/api'` 指定地址。

对应接口为 `POST /api/platform/tenants`。页面使用**平台超级管理员的 Bearer JWT**；可信自动化脚本可使用 `X-Platform-Key`。普通企业 JWT 始终不能作为平台凭证：

```json
{
  "code": "company-a",
  "name": "示例美业企业",
  "adminUsername": "admin",
  "adminName": "企业负责人",
  "adminPassword": "REPLACE_WITH_A_STRONG_PASSWORD",
  "phone": ""
}
```

一次事务创建企业、独立管理员、负责人关联及七个内置角色。`adminUsername` 可选，默认 `admin`；企业编码全局唯一，相同用户名可以在不同企业使用。失败不保留半套企业数据。返回 `tenantId`、`tenantCode`、`adminUserId`、`adminUsername`，不返回密码。

本阶段不提供企业自助注册、负责人转让、删除企业或平台找回密码界面。

## 数据模型：独立表 + 关联表，**无外键**

| 表 | 作用 |
| --- | --- |
| `sys_tenant` | 企业与全局唯一企业编码；主键即租户 ID |
| `sys_user` | 独立账号表；唯一键 `(tenant_id, username)` |
| `sys_tenant_admin` | 企业与唯一负责人账号关联 |
| `sys_department` | 部门 / 门店组织树，使用 `parent_id` 表示层级 |
| `sys_room` | 独立房间表，容量、状态、备注 |
| `sys_department_room` | 部门与房间归属关联，一个房间归属一个部门 |
| `sys_role` | 每家企业独立的角色定义 |
| `sys_permission` | 全局模块权限字典，不存储企业业务数据 |
| `sys_role_permission` | 企业内角色与模块权限关联 |
| `sys_user_department` | 用户与部门 / 门店的多对多关联 |
| `sys_user_role` | 用户 + 角色 + 所属部门的授权关联；内部 `department_id=0` 代表企业范围 |
| `sys_platform_admin` | 平台超级管理员与账号关联，与企业负责人身份分离 |
| `sys_platform_audit` | 平台开通、企业状态、管理员密码与指定企业维护等操作的审计记录，不记录密码 |

共 **13 张身份管理表**，没有 `FOREIGN KEY`、没有级联外键。用复合唯一索引、服务端同租户校验、事务和每企业写锁保证关联完整性。禁止部门形成环；有关联的部门/角色不能直接删除；用户删除为逻辑删除，同时清理角色、部门关联，账号名继续保留。

### 七种内置身份与授权范围

管理员、店长、主理人、前台、美容师、员工、顾客。

- 企业“管理员”角色固定绑定企业负责人，拥有本企业全部模块权限；不可在普通用户管理中删除、停用或解除该身份。平台超管不是某家企业的这七种身份之一。
- 其余内置角色默认仅有“查看首页”，负责人按职责配置权限。可新增自定义角色；内置身份不能删除。
- 同一用户可以属于多个门店，在不同门店担任不同角色。部门范围授权覆盖该部门及其有效下级。
- `users:read`、`departments:read`、`rooms:read/write`、`orders:read/write` 支持部门范围。企业信息、用户维护、组织维护、角色权限管理、`order-settings:read/write` 属于企业范围，不能误设为单店授权。
- “维护”必须同时包含对应“查看”；管理员不能授予自己不具备的权限。
- 每个受保护请求都校验数据库中的企业状态、用户状态、会话版本及当前角色权限，而不是只信 JWT 或前端隐藏按钮。权限撤销即时生效，停用/删除/改密/重置密码使旧登录失效。

## API 契约

平台登录：`POST /api/user/login`

```json
{ "loginType": "PLATFORM", "username": "admin", "password": "YOUR_PLATFORM_PASSWORD" }
```

企业登录：

```json
{ "loginType": "TENANT", "tenantCode": "company-a", "username": "admin", "password": "YOUR_COMPANY_PASSWORD" }
```

返回 `{ code: 200, data: { token, userInfo } }`。`userInfo` 包括 `id/username/nickname/phone/avatar/tenantId/tenantCode/tenantName/owner/platformAdmin/permissions`，不包含密码。平台身份 `platformAdmin=true/tenantId=0/owner=false`；企业身份 `platformAdmin=false/tenantId>0`。企业登录缺少编码或平台登录混入企业编码均返回 400。

以下接口需 `Authorization: Bearer TOKEN`：

- 企业账号的租户范围固定来自已验证的 JWT，伪造其他企业的 `X-Tenant-Id` 返回 403。
- 平台账号调用企业 IAM 数据接口、维护企业订单或核对设置时必须显式携带 `X-Tenant-Id: 目标企业ID`，服务端确认平台身份及目标企业存在；遗漏范围返回 400，不猜测默认企业。平台的订单列表 / 明细 / 门店选项只读接口允许省略该头，汇总全部企业。
- `/iam/me`、`/iam/password` 始终操作真实登录账号，不会因选择企业而将平台账号变成企业账号。

| 路径（`/api/iam` 前缀） | 方法 / 功能 |
| --- | --- |
| `/me` | GET 当前登录身份、用户与权限 |
| `/password` | POST 当前用户改密，`currentPassword/newPassword` |
| `/tenant` | GET 企业信息；PUT 更新企业名称，不修改租户 ID / 企业编码 |
| `/options` | GET 当前账号可见的部门、角色、权限目录及房间可写范围 |
| `/departments`、`/departments/{id}` | GET 组织树数据；POST 新增；PUT 修改；DELETE 删除 |
| `/rooms`、`/rooms/{id}` | GET 分页房间；POST / PUT / DELETE 维护 |
| `/roles`、`/roles/{id}` | GET 分页角色；POST / PUT / DELETE 维护与权限配置 |
| `/users`、`/users/{id}` | GET 分页用户；POST / PUT / DELETE 用户及关联维护 |
| `/users/{id}/password` | POST 管理员重置其他普通用户密码，`password` |

分页返回 `{ records, total, page, pageSize }`，请求支持 `page/pageSize/keyword`，每页最多 100 条。用户额外支持 `departmentId/status`，房间支持 `departmentId`。部门返回当前权限范围内的扁平树节点。

平台接口（`/api/platform` 前缀，除可选的自动开通通道外均要求平台超级管理员 JWT）：

| 路径 | 方法 / 功能 |
| --- | --- |
| `/summary` | GET 企业、企业用户、部门 / 门店、房间数量；平台账号不计为企业员工 |
| `/tenants` | GET 分页企业与负责人，支持搜索 / 状态；POST 开通企业及独立管理员 |
| `/tenants/{id}` | GET 企业详情；PUT 企业名称及状态 |
| `/tenants/{id}/admin` | POST 为尚无负责人的企业创建管理员 |
| `/tenants/{id}/admin/password` | POST 重置企业管理员密码并撤销旧会话 |
| `/data/{users\|departments\|rooms\|roles}` | GET 全部企业分页数据，含所属企业名称、编码、ID；可按 `tenantId` 筛选 |

平台汇总接口不返回密码摘要等认证内部字段。停用企业数据可读，维护前需先启用。

用户写入字段：`username/nickname/phone/email/status/password(仅新增)/departmentIds/roleGrants`。`roleGrants` 每项为 `{ roleId, departmentId }`，**企业范围提交 `departmentId: null`，部门范围提交正整数**。部门顶级节点的 `parentId` 也提交 `null`。编码、角色、部门等所有关联由服务端验证是否属于当前企业。

错误同时使用真实 HTTP 状态码及 JSON `code/message`：400 参数错误、401 登录失效、403 越权、404 当前租户记录不存在、409 重复或关联冲突。

## 订单管理（V3）

前端入口为“顾客经营 → 订单管理”。V3 只新增结构与权限，不创建测试订单，也不改变原账号密码、租户或组织数据。

| 订单表 | 用途 |
| --- | --- |
| `biz_order` | 订单主记录、租户 / 门店、顾客与门店快照、状态、合计 / 已结算金额、核对与业绩版本；企业内订单号唯一 |
| `biz_order_item` | 项目 / 产品等消费内容快照、数量、单价和金额 |
| `biz_order_staff` | 订单服务人员快照，与订单按租户逻辑关联 |
| `biz_order_payment` | 支付方式、业务分类与金额快照；支持混合支付查询 |
| `biz_order_verification_setting` | 企业唯一的核对开关、重新核对规则、乐观锁版本 |
| `biz_order_audit` | 核对 / 取消核对 / 设置变更审计；平台操作同时写平台审计表 |

**六张新表全部没有外键**，与 13 张身份管理表合计 19 张业务表（不计 Flyway 历史表）。同租户查询、部门范围授权和服务端事务约束逻辑关联；有订单的门店不能删除或改为普通部门，可停用并保留历史查询入口。

| 路径（`/api/orders` 前缀） | 方法 / 功能 |
| --- | --- |
| 空路径 | GET 分页订单，返回 `{ records, total, page, pageSize }` |
| `/options` | GET 消费类型、支付方式字典，供截图中的筛选项使用 |
| `/stores` | GET 消费门店分页选项，支持 `keyword/page/pageSize`，只显示当前订单权限覆盖的门店 |
| `/{id}` | GET 订单详情，含消费内容、服务人员和支付明细 |
| `/verification-settings` | GET 当前企业设置；PUT 保存 `{ enabled, recheckAfterPerformanceChange, version }` |
| `/{id}/verification` | POST `{ verified, version }`，核对或取消核对已确认订单 |

### 查询约定

- `tab=all`（默认）查询 `CONFIRMED` 已确认订单；`pending` 查询 `PENDING` 挂单；`balance` 查询已确认且 `total_amount > paid_amount` 的尾款单。
- `searchType=CUSTOMER/ORDER/STAFF`，分别搜索顾客姓名 / 手机 / 编号、订单编号、服务人员姓名 / 手机 / 编号；`keyword` 最长 100 字，LIKE 通配符会被转义。
- `startDate/endDate` 为 `YYYY-MM-DD`，两者一起传；按业务时区（Asia/Shanghai）的起日零点至末日次日零点前查询，包含完整结束日。不固定为参考截图中的历史日期。
- `storeId` 必须是当前可访问的门店；`consumptionType/paymentMethod/verification=VERIFIED|UNVERIFIED` 可组合筛选。消费明细和混合支付用 `EXISTS` 查询，不重复计算订单数量。
- `sortBy=orderNo|orderTime`，`sortDirection=asc|desc`；默认订单时间倒序，以 ID 辅助保证稳定分页。页码至少 1，每页 1–100 条。
- 金额为 `DECIMAL(14,2)`，API 返回两位小数的**字符串**；待收尾款是 `max(合计 - 已结算, 0)`。支付方式的“实收 / 卡耗 / 非实收非卡耗”直接查业务分类，不靠支付名称推断。
- 平台只读可汇总全部企业，响应逐行包含所属企业 ID / 编码 / 名称；选择企业可收窄范围。企业账号的租户来自 JWT，角色再限定可读 / 可核对门店。

### 核对与权限

- `orders:read` 查看列表 / 明细 / 选项，`orders:write` 核对订单；两者支持部门与下级范围。`order-settings:read/write` 仅能授权企业范围。
- V3 为既有企业的 `ADMIN` 角色补齐四个新权限；新开通企业自动取得完整权限目录，其余角色不自动提权。
- 没有设置记录时返回“启用核对、重新分配业绩后需重新核对、版本 0”；首次保存才建记录，每次保存递增版本。
- 核对设置与订单核对都采用企业写锁 / 事务 / 版本校验；旧版本返回 409。挂单、已停用企业及关闭核对时拒绝核对写入，不将 UI 隐藏当作安全边界。
- 核对记录当前 `performance_version`。开启重新核对规则时，只有该版本仍与当前业绩版本一致才算已核对；关闭规则则保留已核对状态。
- 后续**业绩分配模块**写入时必须在同一事务递增订单 `performance_version` 和 `version`；本轮不提供业绩分配、开单、收款、退款或支付网关执行接口。新增的支付 / 消费类型目前仅用于订单查询，不表示对应执行功能已实现。

## 短信管理（V4）

入口为“拓客工具 → 短信”，包含短信设置、发送记录、余额充值。**短信与支付服务商尚未选定**；本版本持久保存设置和待支付充值单，提供真实记录查询与导出，不执行短信发送、扣款、到账或定时任务。现有通知文案尚未映射到服务商审核模板，业务事件与发送回执待后续接入。

V4 新增下列 7 张表，全部无外键；只预置 5 个套餐，不插入测试顾客、发送记录、余额或充值订单。

| 数据表 | 用途 |
| --- | --- |
| `biz_sms_setting` | 企业 + 通知类型唯一；开关、规则 JSON、乐观锁版本、操作者 |
| `biz_sms_record` | 按企业保存发送内容快照、手机号、状态、计费条数、失败原因、服务商消息号；业务消息键防重复 |
| `biz_sms_account` | 企业短信条数余额，非负整数；无记录时返回 0，GET 不创建账户 |
| `biz_sms_package` | 套餐目录、条数、精确金额、上下架与版本 |
| `biz_sms_recharge` | 待支付 / 已支付 / 已取消状态、套餐价格快照、企业内幂等请求键 |
| `biz_sms_credit_ledger` | 为后续真实结算预留的余额流水，当前无写入接口 |
| `biz_sms_audit` | 设置、导出、充值创建与取消审计；平台操作同步记平台审计 |

| 接口（`/api/sms` 前缀） | 行为 |
| --- | --- |
| `GET /status` | 返回发送 / 收款接入状态；当前均为 false |
| `GET /settings` | 返回 4 组共 13 类通知，缺省关闭、版本 0；读取不写表 |
| `PUT /settings/{code}` | 保存 `{ enabled, version, config? }`，旧版本返回 409 |
| `GET /records` | 手机号、日期及分页查询；返回 `{ records, total, page, pageSize }` |
| `GET /records/export` | 导出全部筛选记录，UTF-8 BOM CSV，最多 5000 条，超限要求缩小范围 |
| `GET /billing` | 当前企业或平台汇总条数余额、可用套餐、支付接入状态 |
| `GET /recharges`、`GET /recharges/{id}` | 分页充值历史与单笔记录；只显示授权企业的数据 |
| `POST /recharges` | `{ packageId, packageVersion, idempotencyKey }` 创建待支付单 |
| `POST /recharges/{id}/cancel` | `{ version }` 取消待支付单，版本 / 状态冲突返回 409 |

- 通知分交易提醒（5）、预约提醒（4）、特殊日期提醒（1）和合伙人相关（3）。预约提醒配置 `leadMinutes=1..4320`；生日配置 `advanceDays=0..30`（0 表示当天）、`sendTime=HH:mm`（北京时间，省略默认 09:00，已有时刻保留）、`customBenefitEnabled`、`visitNote` 与 `benefitNote`。自定义福利关闭时可直接保存，使用默认“到店可享受生日权益”；开启时两个文案均需 1–100 字且禁止控制字符。关闭后允许保留原文案供下次编辑，但以关闭标志决定使用默认福利。旧配置缺少开关但有自定义文案时，读取为开启，GET 不回写数据库。有时间规则的通知先完成配置才能开启，单独保存配置不改变外层通知开关。保存开关不会发送消息。
- 记录筛选 `phone` 支持数字 / 加号 / 空格 / 连字符，最长 30 位；`startDate/endDate` 成对传递，默认北京时间本月首日至今天，包含完整结束日。分页为 `page>=1`、`pageSize=1..100`，按发送时间与 ID 倒序。CSV 转义逗号、引号及换行，对公式起始符加文本前缀，返回 `Cache-Control: no-store`，写导出审计。
- 截图套餐预设为 1000 条 / ¥68、3000 条 / ¥180、6000 条 / ¥360、10000 条 / ¥600、50000 条 / ¥2888，**不是已确定的服务商报价**。后端读取套餐价格 / 条数并保存快照，不信任前端金额；金额以十进制字符串返回，余额也以整数字符串返回。
- 幂等键为 UUID，同企业同键重试返回原订单；同键换套餐拒绝。套餐版本不一致拒绝创建，每企业最多 10 笔待支付单，可先取消旧单。创建 / 取消均不写余额表和流水表；不存在支付成功模拟接口或未鉴权的回调入口。
- 权限为 `sms-settings:read/write`、`sms-records:read/write`、`sms-billing:read/write`，其中记录 write 对应报表导出。均为企业级授权，不能借用单店角色读取全企业短信。write 必须同时具备 read；V4 只给已有 ADMIN 角色补齐 6 项权限，其他角色需管理员授权。
- 平台可汇总记录、余额和充值历史；写入设置 / 充值需显式 `X-Tenant-Id`，企业账号禁止切到其他企业。停用企业可由平台查看，不能修改；写操作加企业事务锁并重新加载权限。平台汇总导出在平台审计中使用租户 0，业务短信审计使用空租户表示汇总。

## 验证

```powershell
mvn -B -ntp verify
```

### 短信补充交互验证（V4，2026-09-22）

- `mvn -B -ntp -Dtest=SmsIntegrationTest,SmsMigrationTest verify`：14 项测试通过，包括提前 1 分钟、生日当天、默认 / 自定义福利切换、关闭保留文案、旧 JSON 兼容及租户隔离。前端 `pnpm build` 通过。
- 仅调整已有设置 JSON 契约，无新表、迁移或外键。备份目录为工作区旁 `beauty-saas-backups/20260922-171512-before-sms-interactions/`。更新后端后业务库 27 张表校验一致，MySQL 容器与数据卷不变，外键数仍为 0；Spring Boot 仍为 3.2.5。
- 前端 45 项短信交互用例均取得通过结果（包括修正样式及等待时限定向复测）；已部署服务的 PC / PAD / 手机只读浏览器验收通过，无业务写入或运行时错误。日志与截图位于前端忽略目录 `artifacts/sms-interactions-*`。

### 短信首版历史验证（V4，2026-09-22）

- `mvn -B -ntp verify`：**51 项测试通过，0 失败 / 错误 / 跳过**。新增 11 项短信集成测试及 1 项迁移测试，覆盖默认配置只读、规则校验、版本冲突、日期 / 手机号 / 分页、企业及权限隔离、CSV 转义与 5000 条上限、套餐快照、幂等充值、取消、停用企业和无外键迁移。
- 将升级前 V3 备份恢复到**独立 MySQL 8 临时容器**（仅监听 `127.0.0.1:13309`，tmpfs 数据目录），配合 Java `18080` / Vite `5174` 完成真实迁移及浏览器联调。相同版本的两个并发设置请求分别得到 200 / 409；相同幂等键的并发充值只产生一笔待支付单，账户与余额流水表保持为空。手机号 / 日期筛选、跨企业隔离、UTF-8 CSV 与平台汇总审计均通过。
- 前端 `pnpm build` 通过。新增 35 项短信浏览器用例取得通过结果（修正报表按钮无障碍名称后复跑 5 项）；原有 145 项回归中 144 首次通过，1 项加载超时单独复跑通过。总计 180 项覆盖保留。
- 写入验证仅发生在临时库，完成后停止临时 MySQL / Java / Vite。业务库验收仅登录、读取及取消表单，没有测试短信、充值或余额写入；未实际发送短信或收款。
- 浏览器覆盖 PC、PAD、手机，保留默认蓝色与现有模式切换。尺寸和触控模拟不等于物理设备实机验收。

### 本机短信升级记录（V4，2026-09-22 16:50）

- 更新前最新备份为工作区旁 `beauty-saas-backups/20260922-164826-before-sms/`，包含 `beauty_saas.sql`、`backup-info.json`、`sms-deployment-result.json`。SQL 文件 26,140 字节，容器内外 SHA-256 一致：`38891F59F378148ED5EC740BBC9E28FC3A87410564D865FE8DE09FE99878B0CD`。恢复演练使用稍早的 `20260922-161627-before-sms/`，两份均保留且仅授权本机管理员 / SYSTEM。
- 标准 Docker 多阶段构建下载 Maven 依赖时遇到仓库超时；本机 Maven 测试 / 打包成功后，使用同一份 JAR 与已有 Java 17 运行镜像完成镜像打包，并执行 `docker compose up -d --no-deps --no-build backend`。没有为规避下载问题改动依赖版本或业务配置。后端保持 Spring Boot 3.2.5。
- 业务 MySQL 容器和 `beauty-saas-backend_mysql_data` 数据卷保持不变，只升级后端，未改其他数据库。旧镜像保留为 `beauty-saas-backend:pre-sms-20260922-164826`。
- Flyway V4 成功，新增 7 张短信表，整个业务库外键数为 **0**。只预置 5 档短信套餐，其他短信表为空。原 2 个账号、2 家企业、14 个角色及订单数据保留；除迁移历史、权限字典、管理员权限关联三张预期变化表外，其余 **17 张原表 checksum 全部一致**。
- 权限字典由 15 增至 21，角色权限关联由 42 增至 54，仅为既有两个 ADMIN 角色补齐短信 6 项权限。没有变更账号密码、平台身份或企业负责人关系。
- 使用日常 `5173 → Docker 8080 → beauty_saas` 完成部署后验收：原密码平台登录、明确企业范围的 13 个通知、三个页签、弹窗打开 / 取消、充值历史空态与 PC / PAD / 手机布局，无浏览器运行时错误。结果位于前端未提交的 `artifacts/sms-deployed-result.json` 和对应截图。

### 订单阶段验证（V3，2026-09-22）

- `mvn -B -ntp verify`：**39 项测试通过，0 失败 / 错误 / 跳过**。新增订单集成与迁移测试覆盖分页、混合支付、排序、精确金额、结束日范围、三类订单、企业 / 门店隔离、权限、版本冲突、重新核对、审计、历史门店保护及无外键。
- V2 → V3 迁移测试确认原有业务数据保留、仅 ADMIN 角色补齐权限、重复启动不重复授权、不插入示例订单。已有平台迁移测试固定验证 V2，后续迁移不改变它的断言基准。
- 将本机 V2 备份恢复到**独立 MySQL 8 临时容器**（仅监听 `127.0.0.1:13308`，tmpfs 数据目录），配合 Java `18080` 与 Vite `5174` 完成真实 V3 迁移、企业 / 门店隔离、查询、详情、核对、设置持久化、业绩版本变化和审计联调。两个并发核对请求使用相同版本：一个成功，一个返回 409。
- 真实新增、设置保存、核对 / 取消核对只在临时库执行；业务库页面验收仅登录、查询、打开 / 取消设置。浏览器无运行时错误，未写入测试订单。
- 前端类型检查 / 生产构建通过；35 项新增订单浏览器用例通过。全套回归包含 145 项：首次 140 通过，5 条旧侧栏数量断言适配新增菜单后复跑全部通过。PC / PAD / 手机均有自动化和实际后端链路验证；触控模拟不等于物理设备实机验收。

### 本机订单升级记录（V3，2026-09-22 14:11）

- 升级前最新备份为工作区旁 `beauty-saas-backups/20260922-141021-before-orders/`，包含 `beauty_saas.sql`、`backup-info.json`、`orders-deployment-result.json`。SQL 文件 18,694 字节，容器内外 SHA-256 一致：`08031D5B95D1A27FB03E472E058BF755E13EC8681540A1C087E961D460E9B39E`。恢复演练使用稍早的 `20260922-134510-before-orders/` 快照，两份均保留且仅授权本机管理员 / SYSTEM。
- 仅执行 `docker compose build backend` 与 `docker compose up -d --no-deps backend`。业务 MySQL 容器 ID 和 `beauty-saas-backend_mysql_data` 数据卷保持不变，未修改其他数据库。旧镜像保留为 `beauty-saas-backend:pre-orders-20260922-141021`。
- Flyway V3 成功；6 张订单表为空，整个业务库**外键数为 0**。此次升级前已有 2 个账号、2 家企业、14 个角色，全部保留。除迁移历史、权限字典与管理员权限关联这三张预期变更表外，其余 11 张原表升级前后 checksum 全部一致。
- 权限字典由 11 增至 15，角色权限关联由 34 增至 42，只增加既有两个 ADMIN 角色的四项订单权限。没有改动平台 / 企业账号密码或负责人关系。
- 已通过现有 `5173 → Docker 8080 → beauty_saas` 完成实际部署后的原密码平台登录、汇总订单、指定企业设置读取 / 取消、三个页签及 PC / PAD / 手机布局检查；页面无整页横向溢出。验证材料位于前端未提交的 `artifacts/orders-deployed-result.json` 和对应截图。

### 平台身份阶段的历史验证（V2）

2026-09-22 平台身份修正后已通过 **28 项测试**：平台 / 企业登录分离、平台 JWT 开通企业、汇总分页查询、显式企业范围写入、跨企业关联拒绝、企业账号越权拒绝、企业停用和密码重置撤销会话、原始管理员 V1 → V2 迁移及其他企业管理员不被提升。也包括此前用户 / 部门 / 房间 / 角色的关联、部门环、权限范围、无外键、旧 MD5 升级、事务回滚和 CORS 检查。集成测试使用 **独立 H2 内存数据库的 MySQL 模式**，不连接本机业务库。

另外将迁移前业务快照恢复到**独立 MySQL 8 临时容器**（端口 13317，tmpfs 数据目录），通过独立 Java（18081）和 Vite（5174）验证真实 V2 迁移及浏览器端到端操作：原密码登录平台、为保留企业开通新管理员、开通第二家企业、平台维护指定企业部门和房间、汇总查看各企业、企业独立登录和隔离、管理员密码重置撤销会话、停用企业仍可由平台查看。未使用接口模拟，未发现浏览器运行时错误；测试写入只发生在临时数据库。

### 平台身份阶段的历史升级记录（V2，2026-09-22）

平台身份修正已在本机实际部署：先备份原 `beauty_saas` 并核对容器内外 SHA-256，一份快照恢复到独立临时 MySQL 验证迁移与真实界面操作，再于更新前补做最新备份，最后执行 `docker compose up -d --no-deps backend`。

- 该次升级前备份目录为工作区旁的 `beauty-saas-backups/20260922-123104-before-platform-admin/`；包含 `beauty_saas.sql`、`backup-info.json` 和 `deployment-result.json`，不在 Git 仓库中。恢复演练使用 `20260922-121757-before-platform-admin/` 快照；两份均保留。
- 备份校验值、旧镜像标签和 MySQL 容器标识记录在 `backup-info.json`，原后端镜像已保留，备份目录仅授权本机管理员和 SYSTEM。
- 业务库 Flyway **V2 已成功执行**；原有 **1 个账号保留**，原 `admin` 已变为平台超管，账号 ID `1`、保留平台范围 `0`，密码不变。原 `yulequan` / 租户 ID `1` 企业及 7 个内置角色、11 个模块权限仍保留，企业管理员待平台单独开通。
- 13 张身份管理表的**外键数量为 0**。MySQL 容器 ID 和 `beauty-saas-backend_mysql_data` 数据卷均未变化；未修改其他数据库，未向业务库写入测试企业、员工、部门或房间。
- 通过实际 Vite → Docker 后端 → 业务 MySQL 链路，检查 PC、PAD、手机尺寸下的原密码平台登录、平台身份显示、开通表单 / Esc、全部企业数据及指定企业五个管理页签。使用 Chrome 尺寸 / 触控模拟，不代表物理手机实机验收；该轮限制为登录、读取及取消表单。
- 本机后端 `.env` 保存随机 JWT 密钥、可选自动开通密钥和局域网白名单，已被 Git / Docker 构建上下文忽略；不提交该文件，也不向企业用户提供平台密钥。
- 更早的 V1 升级备份 `20260922-110053-before-tenant-iam/` 是历史快照，不能与本次 V2 迁移前快照混用。

回滚涉及数据库版本与密码格式：不能仅换回旧镜像就认为兼容。必要时应先停止业务写入，保留升级后的备份，在核对快照时间和数据差异后恢复对应数据库与旧镜像；不要直接覆盖已经产生新数据的库。

## 官方参考

- [Spring Security 6.2.4 BCrypt 实现](https://github.com/spring-projects/spring-security/blob/6.2.4/crypto/src/main/java/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.java)
- [Flyway 9.22.3 Java 迁移基类](https://github.com/flyway/flyway/blob/flyway-9.22.3/flyway-core/src/main/java/org/flywaydb/core/api/migration/BaseJavaMigration.java)
