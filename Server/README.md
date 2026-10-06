# FragmentsPicker Server

后端运行基线：Java 21+、Spring Boot 3.5、Maven Wrapper。Windows 使用 `mvnw.cmd`，Linux/macOS 使用 `./mvnw`。

```powershell
cd Server
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

默认监听 8080，可通过 `SERVER_PORT` 调整。健康检查：`GET http://localhost:8080/actuator/health`；成功返回 `status: UP`，另包含探针组信息。仅开放 health，不公开环境变量或配置详情。

运行可执行 JAR：

```powershell
java -jar target/fragpicker-server-0.1.0-SNAPSHOT.jar
```

默认 `bootstrap` profile 可以在没有数据库和云凭据的情况下运行，仅供启动检查。实际业务使用 `database` profile；缺少必填数据库环境变量时启动失败，不退回临时数据库。

## 本机 MySQL 初始化

本机 MySQL 8.0 或 8.4 可用。先用管理员账号执行：

```powershell
mysql -u root -p --execute="source scripts/init-local-mysql.sql"
```

初始化脚本仅创建 `fragpicker` 数据库，不覆盖已有数据。账号和授权语句提供为注释，请在本机填写密码后单独执行；运行账号不使用 root。数据库表由 Flyway 在启动时自动创建和校验。

在启动后端的终端配置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，再执行：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/fragpicker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = 'fragpicker'
# DB_PASSWORD 通过本机环境或安全输入配置，不提交到文件。
$env:JWT_SIGNING_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
# 同一个部署需保持该签名密钥稳定；生产环境用秘密存储注入。
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=database'
```

云服务器连接应配置 TLS；`allowPublicKeyRetrieval=true&sslMode=DISABLED` 只用于脚本创建的本机隔离测试实例。

## 真实 MySQL 集成验证

```powershell
.\scripts\Test-MySql.ps1
```

脚本使用已安装的 MySQL 可执行文件，在项目忽略目录 `.tools` 创建新的数据目录并绑定随机本机端口，使用随机测试密码，然后运行 `verify` 并关闭实例。它不访问现有 MySQL 的数据目录，不修改现有服务，不使用已有账号密码。日志与测试数据保留在 `.tools/mysql-test-*`，便于失败诊断。

普通 `mvnw verify` 运行无数据库启动测试；没有 `DB_TEST_URL` 时明确跳过数据库集成测试。交付前使用上面的脚本在真实 MySQL 上完成验证。后续业务表随各模块通过新增迁移脚本演进，不修改已交付迁移。

## 注册接口

`POST /api/v1/auth/register`（需要 `database` profile）：JSON 请求包含 `username` 和 `password`。用户名为3～32位字母、数字或下划线，大小写不区分；密码为8～64个字符且 UTF-8 编码不超过72字节。

成功返回 HTTP 201 和 `id`、`username`、`businessZone`。重复用户名返回 409 / `USERNAME_TAKEN`，非法输入返回 400。数据库只保存 BCrypt 盐化哈希，响应不包含密码或哈希。

## 登录与鉴权

`POST /api/v1/auth/login` 接收用户名和密码，成功返回 `accessToken`、`tokenType: Bearer`、`expiresIn`（秒）和用户资料。访问令牌默认有效15分钟；受保护请求通过 `Authorization: Bearer <token>` 携带令牌。

签名采用 HS256，`JWT_SIGNING_KEY` 必须是至少32随机字节的 Base64 编码值，没有默认密钥。鉴权检查签名、过期时间、发行方、受众、账号状态和数据库中的令牌版本。错误密码和不存在账号统一返回 401 / `INVALID_CREDENTIALS`。

`bootstrap` 仅用于健康检查，不加载业务接口。实际 `database` profile 的业务接口默认需要鉴权，仅注册、登录、刷新和健康检查公开。

## 刷新令牌

登录响应额外包含 `refreshToken` 和 `refreshExpiresIn`。`POST /api/v1/auth/refresh` 接收 `{"refreshToken":"<token>"}`，成功返回新的访问/刷新令牌。该请求不携带旧的 Authorization 头。

刷新令牌有效期默认30天，轮换保留初次登录的绝对到期时间；每个令牌仅能使用一次，数据库只存 SHA-256 哈希。已使用令牌再次提交会撤销该用户所有会话并立即使已有访问令牌失效，要求重新登录。Android 会串行化刷新请求，避免并发误用旧令牌。

## 退出登录

`POST /api/v1/auth/logout` 使用当前访问令牌鉴权，不接收用户 ID。成功返回 HTTP 204，撤销当前账号全部刷新会话并递增令牌版本，因此访问令牌立即失效；其他账号不受影响。Android 客户端应在退出后清理本地会话。重复提交已撤销令牌返回 401。

## 环境变量

`application.yml` 中的凭据只引用环境变量。测试 AppKey、Bucket 和 AccessKey 同样通过环境变量配置，不提交真实值。

| 环境变量 | 用途 |
| --- | --- |
| SERVER_PORT | HTTP 端口，默认 8080 |
| DB_URL / DB_USERNAME / DB_PASSWORD | 本机 MySQL 连接与账号 |
| JWT_SIGNING_KEY | 至少32字节随机密钥的 Base64 值 |
| JWT_ACCESS_TOKEN_TTL | 访问令牌时长，默认15分钟 |
| JWT_REFRESH_TOKEN_TTL | 刷新会话绝对有效期，默认30天 |
| PARSEVIDEO_BASE_URL | 已部署的视频解析服务地址 |
| TINGWU_APP_KEY | 听悟应用 AppKey |
| OSS_BUCKET / OSS_ENDPOINT | 私有对象存储 Bucket 与 Endpoint |
| ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET | 阿里云访问凭据 |
| AI_API_KEY | 百炼 API Key |
| AI_CHAT_MODEL / AI_EMBEDDING_MODEL | 百炼聊天与向量模型名称 |

本模块只声明外部配置占位符，尚未调用这些服务。设置环境变量后重启进程生效。真实凭据、`application-local.yml`、`.env`、构建输出与签名文件均在 Git 忽略列表中。
