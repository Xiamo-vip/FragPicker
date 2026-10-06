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

## 当前用户

`GET /api/v1/users/me` 携带访问令牌，返回当前用户的 `id`、`username`、`businessZone`，不接受其他用户 ID，不返回密码或哈希。

## 环境变量

`application.yml` 中的凭据只引用环境变量。测试 AppKey、Bucket 和 AccessKey 同样通过环境变量配置，不提交真实值。

| 环境变量 | 用途 |
| --- | --- |
| SERVER_PORT | HTTP 端口，默认 8080 |
| DB_URL / DB_USERNAME / DB_PASSWORD | 本机 MySQL 连接与账号 |
| JWT_SIGNING_KEY | 至少32字节随机密钥的 Base64 值 |
| JWT_ACCESS_TOKEN_TTL | 访问令牌时长，默认15分钟 |
| JWT_REFRESH_TOKEN_TTL | 刷新会话绝对有效期，默认30天 |
| INGESTION_ALLOWED_HOSTS | 逗号分隔的投喂平台域名，与解析服务路由同步 |
| INGESTION_WORKER_ENABLED | 自动解析投喂任务，默认 false，须同时启用 parsevideo |
| INGESTION_POLL_DELAY / INGESTION_LEASE_DURATION | 领取间隔 / 租约时长，默认2秒 / 5分钟 |
| INGESTION_MAX_ATTEMPTS / INGESTION_RETRY_BASE_DELAY | 解析尝试上限 / 退避基数，默认3次 / 10秒 |
| PARSEVIDEO_ENABLED / PARSEVIDEO_BASE_URL | 启用解析客户端（默认 false）及服务根地址 |
| PARSEVIDEO_CONNECT_TIMEOUT / PARSEVIDEO_READ_TIMEOUT | 连接与读取超时，默认5秒 / 45秒 |
| PARSEVIDEO_MAX_RESPONSE_BYTES | 解析响应上限，默认1 MiB |
| PARSEVIDEO_USERNAME / PARSEVIDEO_PASSWORD | 可选 Basic Auth 凭据，必须成对配置 |
| TINGWU_APP_KEY | 听悟应用 AppKey |
| OSS_BUCKET / OSS_ENDPOINT | 私有对象存储 Bucket 与 Endpoint |
| ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET | 阿里云访问凭据 |
| AI_CHAT_ENABLED | 启用聊天模型，默认 false |
| AI_CHAT_BASE_URL | OpenAI 兼容接口根地址，默认 https://api.deepseek.com |
| AI_CHAT_API_KEY | 当前聊天供应商的 API Key |
| AI_CHAT_MODEL | 聊天模型名，启用时必填，无默认模型 |
| AI_CHAT_TIMEOUT | 调用超时，默认60秒，允许1秒～5分钟 |
| AI_CHAT_MAX_OUTPUT_TOKENS | 最大输出 token 数，默认4096，允许1～32768 |

听悟和 OSS 目前仍为配置占位符。设置环境变量后重启进程生效。真实凭据、`application-local.yml`、`.env`、构建输出与签名文件均在 Git 忽略列表中。

## OpenAI 兼容聊天模型

聊天使用 LangChain4j 的 `ChatModel` 和 `StreamingChatModel`，默认供应商地址为 DeepSeek。替换 `AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL` 即可切换兼容 Chat Completions 和工具调用的供应商；Base URL 是接口根地址，不应包含 `/chat/completions`。可保留供应商要求的 `/v1` 或其他路径前缀。

设置 `AI_CHAT_ENABLED=true` 后，地址、密钥、模型名和请求边界必须有效，否则启动失败。默认关闭时不要求云凭据，也不创建模型客户端。远程地址要求 HTTPS，本机协议测试允许 loopback HTTP。请求/响应日志关闭，同步请求不自动重试；业务层将明确处理付费调用的重试。DeepSeek 的 `reasoning_content` 在工具轮次之间由 SDK 保留，不作为用户可见回答。

[DeepSeek 接口文档](https://api-docs.deepseek.com/zh-cn/)提供当前模型名称；模型名通过环境配置，不在代码中固定。embedding 使用 LangChain4j 本地模型的独立模块，不使用聊天 API Key。

本次模型适配器通过本机 HTTP 契约测试覆盖普通回复、工具参数和推理字段保留、流式结束与错误、超时及无自动重试；这些测试不代表已完成真实 DeepSeek 验证。尚未提供对话业务接口、历史检索 Tools 或消息持久化，它们按后续独立模块交付。

## 本地中文 embedding

使用 LangChain4j 提供的量化 `BgeSmallZhV15QuantizedEmbeddingModel`（512维）。ONNX 模型与 tokenizer 随 Maven 依赖进入可执行 JAR；首次构建需要下载依赖，运行时无需请求百炼、Hugging Face 或其他云模型，不需要 embedding API Key。Bean 延迟初始化，基础启动检查不加载模型。

`LocalEmbeddingService` 区分查询和文档：查询加中文检索指令，文档保持原文；返回有限、归一化向量。模型标识为 `bge-small-zh-v1.5-q@langchain4j-1.21.0-beta31`，后续索引须一并记录此标识和维度，切换模型后重建索引。

为控制推理负载，文档片段限384 Unicode 码点，查询限256码点，批次限16条，推理串行执行；这些是应用输入边界，不等同于 tokenizer 的 token 数。长转写必须由后续索引模块分片，不能静默截断。此模块提供真实本地模型推理与中文检索样例测试，数据库索引、用户数据筛选和检索接口仍在后续模块实现。

模型依据：[LangChain4j 本地 ONNX 模型](https://docs.langchain4j.dev/integrations/embedding-models/in-process/)、[BGE 中文模型说明](https://huggingface.co/BAAI/bge-small-zh-v1.5)。中文样例用于验证基础召回顺序，不代表所有资料都达到固定准确率。

## parsevideo 解析适配器

设置 `PARSEVIDEO_ENABLED=true` 和服务根地址 `PARSEVIDEO_BASE_URL` 后启用 `ParseVideoClient`。调用 `GET /video/share/url/parse?url=...`，完整编码分享 URL，保留服务地址的路径前缀。按部署服务的 `code/msg/data` 包装提取 `video_url`、`cover_url`、`title`、`author.name/uid/avatar`；缺失的可选元数据保留为空，不虚构作者或标题。纯图集暂不进入视频转写流程。

连接超时允许1～30秒，读取空闲超时允许1～120秒，响应上限允许16 KiB～4 MiB；禁止自动重定向和重试。错误映射为稳定代码和可重试标记，不把供应商内部消息、访问凭据或临时签名媒体地址写入异常与日志。可选 Basic Auth 只允许 HTTPS 或 loopback HTTP。媒体 URL 在此阶段仅解析；后续下载阶段还须校验网络目标和重定向，不能直接信任解析结果。

契约测试使用本机 HTTP 服务；真实部署验证可在运行后端测试前设置 `PARSEVIDEO_TEST_BASE_URL`，使用[解析项目文档](https://github.com/baige778/parse-video-py)的公开 B 站示例完成验证。未设置时明确跳过该真实测试。此模块尚未下载媒体或调用听悟，后续通过持久化投喂任务接入。

## 视频投喂入库

`POST /api/v1/fragments` 使用 Bearer 鉴权，要求 UUID 格式的 `Idempotency-Key` 头；JSON 包含 `shareText`（最多4096字符）和可选 `note`（最多1000字符）。提取分享文本中的一个 HTTP(S) 链接，按解析项目路由校验平台域名，不开放任意 URL。默认域名覆盖解析项目的多平台路由；服务升级时可用 `INGESTION_ALLOWED_HOSTS` 更新完整列表。

成功返回 HTTP 202，包含 `fragmentId`、`jobId`、`status`、`businessDate`、`duplicate`。用户身份来自认证上下文，不接受请求中的用户 ID。UTC 保存时间，业务日期与当时用户时区一并固定保存。

记录、任务、幂等凭证在同一事务入库。同一键与同一规范化链接/备注重试返回已有记录；同一键提交不同内容返回409 `IDEMPOTENCY_CONFLICT`。同一用户重复链接返回已有记录，不新增任务，也不替换原备注和归属日期；其他用户拥有独立记录。规范化只调整协议/主机大小写、默认端口和片段，不删除或重排查询参数，以免破坏分享链接。平台资源 ID 去重在解析阶段继续补充。

当前任务持久化为 `QUEUED`，此接口不调用云服务。后台解析通过下面的工作线程处理；媒体保存、转写和增强按后续独立模块接入。排队成功不等同于视频已总结完成。真实 MySQL 测试覆盖幂等、并发、用户隔离与任务写入失败时的事务回滚。

## 投喂状态查询

`GET /api/v1/fragments/{id}` 使用 Bearer 鉴权，返回当前用户的原链接、域名、备注、归属日期与时区、UTC 创建时间、处理阶段、尝试次数及稳定错误码。接口不接受查询参数中的用户身份；其他用户的记录与不存在的记录统一返回404。响应禁止缓存，不公开任务租约、工作线程身份或内部哈希。

## 后台视频解析任务

在 `database` profile 中设置 `PARSEVIDEO_ENABLED=true`、`PARSEVIDEO_BASE_URL` 和 `INGESTION_WORKER_ENABLED=true` 后，自动按固定间隔领取任务。默认状态流程为 `QUEUED → PARSING → MEDIA_PENDING`；`MEDIA_PENDING` 表示已保存解析元数据，等待后续私有 OSS 保存阶段。工作线程关闭时投喂仍可入库，但保持排队。

使用 MySQL `FOR UPDATE SKIP LOCKED` 领取一条任务；领取事务结束后才请求解析服务，网络等待不持有数据库锁。每次领取递增尝试次数和版本，并分配随机租约标识。租约时间以数据库 UTC 为准；进程崩溃后过期任务可重新领取。成功或失败回写都核对用户、任务、版本、租约及到期时间，过期旧线程不能覆盖新结果。解析成功时元数据、任务阶段和投喂状态在同一事务提交。

`fragment_video_metadata` 保存实际标题、视频地址、封面、作者名称、作者 ID 和头像；缺失的可选字段为空。这里的地址可能是短期签名 URL，仅供后续媒体阶段使用，不代表已经长期保存，也不直接返回给客户端。平台资源 ID 去重仍待补充（作者 ID 不是视频 ID）。

临时错误重排到 `QUEUED`，按基数的指数退避，最长1小时；默认最多3次领取。永久错误或次数用尽转为 `FAILED`。错误码使用 `PARSE_` 前缀；崩溃导致最后一次租约到期记录 `PARSE_LEASE_EXPIRED`。不会记录供应商原始错误消息、媒体签名 URL 或请求内容。轮询异常保留可恢复租约。

领取间隔允许250毫秒～1分钟，租约15秒～10分钟，尝试次数1～10，退避基数1秒～10分钟。启用时租约至少超过连接和读取空闲超时之和5秒；持续输出的 HTTP 响应可能超过空闲超时，最终仍由租约校验拒绝过期结果。配置不满足约束则启动失败。

真实 MySQL 测试覆盖并发单次领取、跳过被锁任务、过期租约隔离、重试上限、永久失败、崩溃恢复和元数据事务回滚；设置 `PARSEVIDEO_TEST_BASE_URL` 还验证真实部署服务到数据库的解析链路。OSS 和听悟链路尚未完成，不应将 `MEDIA_PENDING` 展示为总结成功。
