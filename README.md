# 美业SaaS系统 - 后端

## 项目介绍
美业SaaS管理系统后端，基于Spring Boot 3.2.5开发。

## 技术栈
- Spring Boot 3.2.5
- MyBatis Plus 3.5.5
- MySQL 8.0+
- JWT
- Lombok

## 快速开始

### 1. 数据库配置
执行SQL脚本初始化数据库：
```bash
mysql --default-character-set=utf8mb4 -u root -p < src/main/resources/sql/init.sql
```

如果旧数据库中默认 `admin` 账号的昵称显示乱码，可执行一次定向修复脚本：
```bash
mysql --default-character-set=utf8mb4 -u root -p < src/main/resources/sql/repair-admin-nickname.sql
```
脚本只修复 `beauty_saas.sys_user` 中已知的默认管理员乱码值，可重复执行，不覆盖已修改的昵称。

### 2. 修改配置
修改 `src/main/resources/application.yml` 中的数据库连接信息：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/beauty_saas?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: root
    password: your_password
```

### 3. 启动项目
```bash
mvn spring-boot:run
```

### Docker 启动
在项目根目录执行：
```bash
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

停止服务时使用 `docker compose down` 即可保留 MySQL 数据卷；不要加 `-v`，否则会删除本地数据库卷。

访问地址：http://localhost:8080/api

## 测试账号
- 用户名：admin
- 密码：admin123

## API文档
### 登录接口
- 接口地址：POST /api/user/login
- 请求参数：
```json
{
  "username": "admin",
  "password": "admin123"
}
```
- 响应结果：
```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGc...",
    "userInfo": {
      "id": 1,
      "username": "admin",
      "nickname": "管理员",
      "avatar": null
    }
  },
  "timestamp": 1234567890123
}
```
