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
| MEDIA_TEMP_DIR | 媒体暂存目录，默认系统临时目录下 fragpicker-media |
| MEDIA_MAX_VIDEO_BYTES / MEDIA_MAX_COVER_BYTES | 下载大小上限，默认1 GiB / 10 MiB |
| MEDIA_CONNECT_TIMEOUT / MEDIA_READ_TIMEOUT / MEDIA_TOTAL_TIMEOUT | 连接、读取空闲、HTTP 总时限，默认5秒 / 30秒 / 5分钟 |
| MEDIA_MAX_REDIRECTS | 手动检查的重定向上限，默认3，允许0～5 |
| TINGWU_APP_KEY | 听悟应用 AppKey |
| OSS_BUCKET / OSS_ENDPOINT | 私有对象存储 Bucket 与 Endpoint |
| OSS_ENABLED | 启用 OSS 适配器，默认 false，启动时验证私有 Bucket |
| OSS_MAX_VIDEO_BYTES / OSS_MAX_COVER_BYTES | 单次上传资源保护上限，默认1 GiB / 10 MiB |
| OSS_SIGNED_URL_TTL | GET 签名地址有效期，默认5分钟，允许30秒～1小时 |
| ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET | 阿里云访问凭据 |
| ALIBABA_CLOUD_SECURITY_TOKEN | 可选 STS 临时凭据的安全令牌 |
| AI_CHAT_ENABLED | 启用聊天模型，默认 false |
| AI_CHAT_BASE_URL | OpenAI 兼容接口根地址，默认 https://api.deepseek.com |
| AI_CHAT_API_KEY | 当前聊天供应商的 API Key |
| AI_CHAT_MODEL | 聊天模型名，启用时必填，无默认模型 |
| AI_CHAT_TIMEOUT | 调用超时，默认60秒，允许1秒～5分钟 |
| AI_CHAT_MAX_OUTPUT_TOKENS | 最大输出 token 数，默认4096，允许1～32768 |
| MEDIA_WORKER_ENABLED | 启用后台媒体保存任务，默认 false；要求 parsevideo 与 OSS 同时启用 |
| MEDIA_WORKER_POLL_DELAY / MEDIA_WORKER_LEASE_DURATION | 轮询间隔与租约，默认2秒 / 30分钟 |
| MEDIA_WORKER_MAX_ATTEMPTS / MEDIA_WORKER_RETRY_BASE_DELAY | 媒体阶段独立尝试上限与退避基数，默认3次 / 10秒 |

听悟、OSS 适配器见下文。设置环境变量后重启进程生效。真实凭据、`application-local.yml`、`.env`、构建输出与签名文件均在 Git 忽略列表中。

## OpenAI 兼容聊天模型

聊天使用 LangChain4j 的 `ChatModel` 和 `StreamingChatModel`，默认供应商地址为 DeepSeek。替换 `AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL` 即可切换兼容 Chat Completions 和工具调用的供应商；Base URL 是接口根地址，不应包含 `/chat/completions`。可保留供应商要求的 `/v1` 或其他路径前缀。

设置 `AI_CHAT_ENABLED=true` 后，地址、密钥、模型名和请求边界必须有效，否则启动失败。默认关闭时不要求云凭据，也不创建模型客户端。远程地址要求 HTTPS，本机协议测试允许 loopback HTTP。请求/响应日志关闭，同步请求不自动重试；业务层将明确处理付费调用的重试。DeepSeek 的 `reasoning_content` 在工具轮次之间由 SDK 保留，不作为用户可见回答。

[DeepSeek 接口文档](https://api-docs.deepseek.com/zh-cn/)提供当前模型名称；模型名通过环境配置，不在代码中固定。embedding 使用 LangChain4j 本地模型的独立模块，不使用聊天 API Key。

本机 HTTP 契约测试覆盖普通回复、工具参数和推理字段保留、流式结束与错误、超时及无自动重试。2026-10-06 使用本机配置的 `deepseek-flash` 完成真实 DeepSeek 验证：中文回复、流式文本与结束回调、工具名称和 JSON 参数、回传工具结果后的续答均通过；本轮聊天模块13项测试全部通过。

真实测试默认跳过，显式设置 `AI_CHAT_TEST_ENABLED=true`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL` 后，在 `Server` 下运行 `./mvnw.cmd "-Dtest=ChatModelLiveIntegrationTest,ChatModelContractTest,ChatModelConfigurationTest" test`。可用 `AI_CHAT_BASE_URL` 切换供应商；测试会发起4次付费模型请求，不做自动重试，只使用合成样例，不输出凭据或供应商原始错误正文。新设置的 Windows 用户环境变量需要重启终端或显式读入当前进程，不能只以旧进程中变量为空判断配置缺失。

工具结果在真实协议测试中为合成数据，尚未提供对话业务接口、数据库检索 Tools 或消息持久化，不能将此项验证视为用户历史检索已经完成。这些业务按后续独立模块交付。

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

当前任务持久化为 `QUEUED`，此接口不调用云服务。后台解析和媒体保存通过下面的工作线程处理；转写和增强按后续独立模块接入。排队成功不等同于视频已总结完成。真实 MySQL 测试覆盖幂等、并发、用户隔离与任务写入失败时的事务回滚。

## 投喂状态查询

`GET /api/v1/fragments/{id}` 使用 Bearer 鉴权，返回当前用户的原链接、域名、备注、归属日期与时区、UTC 创建时间、处理阶段、尝试次数及稳定错误码。接口不接受查询参数中的用户身份；其他用户的记录与不存在的记录统一返回404。响应禁止缓存，不公开任务租约、工作线程身份或内部哈希。

## 后台视频解析任务

在 `database` profile 中设置 `PARSEVIDEO_ENABLED=true`、`PARSEVIDEO_BASE_URL` 和 `INGESTION_WORKER_ENABLED=true` 后，自动按固定间隔领取任务。默认状态流程为 `QUEUED → PARSING → MEDIA_PENDING`；`MEDIA_PENDING` 表示已保存解析元数据，等待后续私有 OSS 保存阶段。工作线程关闭时投喂仍可入库，但保持排队。

使用 MySQL `FOR UPDATE SKIP LOCKED` 领取一条任务；领取事务结束后才请求解析服务，网络等待不持有数据库锁。每次领取递增尝试次数和版本，并分配随机租约标识。租约时间以数据库 UTC 为准；进程崩溃后过期任务可重新领取。成功或失败回写都核对用户、任务、版本、租约及到期时间，过期旧线程不能覆盖新结果。解析成功时元数据、任务阶段和投喂状态在同一事务提交。

`fragment_video_metadata` 保存实际标题、视频地址、封面、作者名称、作者 ID 和头像；缺失的可选字段为空。这里的地址可能是短期签名 URL，仅供后续媒体阶段使用，不代表已经长期保存，也不直接返回给客户端。平台资源 ID 去重仍待补充（作者 ID 不是视频 ID）。

临时错误重排到 `QUEUED`，按基数的指数退避，最长1小时；默认最多3次领取。永久错误或次数用尽转为 `FAILED`。错误码使用 `PARSE_` 前缀；崩溃导致最后一次租约到期记录 `PARSE_LEASE_EXPIRED`。不会记录供应商原始错误消息、媒体签名 URL 或请求内容。轮询异常保留可恢复租约。

领取间隔允许250毫秒～1分钟，租约15秒～10分钟，尝试次数1～10，退避基数1秒～10分钟。启用时租约至少超过连接和读取空闲超时之和5秒；持续输出的 HTTP 响应可能超过空闲超时，最终仍由租约校验拒绝过期结果。配置不满足约束则启动失败。

真实 MySQL 测试覆盖并发单次领取、跳过被锁任务、过期租约隔离、重试上限、永久失败、崩溃恢复和元数据事务回滚；设置 `PARSEVIDEO_TEST_BASE_URL` 还验证真实部署服务到数据库的解析链路。`MEDIA_PENDING` 表示等待下文的媒体工作线程，不应展示为总结成功。

## 私有 OSS 媒体适配器

`OSS_ENABLED=true` 时，必须配置 Bucket、Endpoint 和阿里云访问凭据。支持公网地域 Endpoint（例如 `oss-cn-shenzhen.aliyuncs.com`），自动规范为 HTTPS 并推导 V4 签名地域；不接受任意第三方域名、HTTP、内网 Endpoint 或附加查询。启用后启动时读取 Bucket ACL，必须为私有；不会修改现有 Bucket 权限。SDK 使用 V4 签名和 CRC64 校验，关闭自动重试与 SDK 原始日志。

`OssMediaStorage` 是供后端业务使用的内部适配器，尚不是对外上传/播放接口。调用方必须先验证数据库所有权。上传本机暂存文件，流式计算 SHA-256 与 MD5；对象路径为 `users/{userId}/fragments/{fragmentId}/{video|cover}/{sha256}`，重复上传内容的路径稳定。MD5 随请求提交，检测两次读取之间的内容变化；对象显式设置 private ACL。只接受受限的 MIME 类型、非空普通文件和配置大小上限，这些检查不代替后续下载阶段的媒体内容验证。

上传返回 Bucket、对象键、大小、内容哈希和类型，业务数据库应保存这些稳定信息。按用户、记录及媒体类型校验对象键后，才能生成限时 GET 地址或删除对象；URL 的 `toString` 脱敏，异常只包含稳定错误码和重试标记。签名地址须在播放时重新获取，不长期写入数据库。默认单次上传上限是工程资源边界，不是用户已确认的业务额度。

契约测试通过真实 SDK 请求本机 HTTP 服务，检查 V4 签名、私有 ACL、完整性头、作用域、错误和无自动重试。真实验证需设置 `OSS_TEST_ENABLED=true`、`OSS_BUCKET`、`OSS_ENDPOINT` 及访问凭据，再运行 `mvnw verify`；测试只上传一个极小 PNG 到随机测试命名空间，验证签名下载、匿名访问拒绝和过期拒绝，最后删除本次创建的对象。未启用时该真实测试明确跳过；Bucket 不存在、非私有或凭据权限不足时测试失败，不调整已有设置。

所需权限至少包含 Bucket ACL 查询以及目标对象前缀的 PutObject、GetObject、DeleteObject（删除用于测试清理/将来用户删除）；真实验证不得用模拟替代。下载和任务推进由下文工作线程接入，鉴权播放 API 仍在后续独立模块实现。

2026-10-06 开发验证已使用用户配置的深圳测试 Bucket 完成真实上传、重复上传、签名下载、匿名403、过期403与本次对象清理；凭据仅注入测试进程环境，没有写入源码或仓库。真实上传验证使用极小 PNG，不代表大视频、网络目标校验或整条转写链路已完成。

## 媒体下载适配器

`SafeMediaDownloader` 将解析后的资源流式写入独立暂存文件，调用方通过 try-with-resources 关闭 `DownloadedMedia` 时删除文件。失败会中止 HTTP 请求并删除已创建文件；不会为释放连接继续读取超大响应。下载适配器由下文媒体工作线程调用。

仅接受标准端口的 HTTP(S) URL，拒绝凭据、片段及本机域名。HTTP socket 实际使用经 `PublicNetworkPolicy` 验证的 DNS 地址，混合公网/私网结果整体拒绝；IPv4 私网、loopback、链路本地、共享/保留/文档/多播地址与 IPv6 本地、文档及过渡地址均被拦截。每次重定向重新校验，禁止 HTTPS 降到 HTTP。系统代理、自动重试、自动重定向、cookie 和自动解压均不启用，避免绕过连接目标与大小校验。部署时仍应限制后端出站网络。

同时检查 Content-Length 与实际读取大小；总 HTTP 时限通过独立定时器中止当前请求，也适用于持续少量输出的响应。DNS 查询本身受系统解析时限影响。默认资源上限是工程边界，可以用环境变量调整，允许视频最多5 GiB、封面最多50 MiB。只发送原平台 origin 作为 Referer，不携带分享链接的查询参数、用户备注或认证凭据。原始 HTTP 日志关闭，失败只保留稳定错误码。

根据文件头识别 MP4/QuickTime、Matroska/WebM、FLV，以及 PNG、JPEG、WebP、GIF，不信任响应声明的 MIME。格式识别不是完整解码验证；时长、轨道与可转写性仍需后续处理。HTML、图集、HLS/M3U8 与不识别的格式返回 `UNSUPPORTED_CONTENT`，不会保存为可播放成功记录。HTTP401/403 标记来源失效，后续媒体任务应重新解析分享链接获取资源，而非无限重试旧地址。

设置 `MEDIA_TEST_PARSEVIDEO_BASE_URL` 后运行媒体真实测试，会调用解析服务的公开 B 站样本，下载实际封面和视频，校验文件类型并删除本次文件；未设置时明确跳过。2026-10-06 已用原生产连接校验下载651,508字节封面与51,973,319字节视频，12项本模块及启动验证通过。本机代理 Fake-IP DNS 曾返回198.18.x.x，被正确拦截；最终验证仅对测试 JVM 通过 `jdk.net.hosts.file` 注入阿里公共 DNS 的当时公网结果，未修改系统 DNS、未放开保留地址段。这些临时映射位于忽略目录，不能作为长期部署配置。

## 后台媒体保存任务

数据库迁移 V4 为任务增加独立的 `media_attempt_count`，新增 `fragment_stored_media` 保存用户、记录、VIDEO/COVER 类型、Bucket、对象键、大小、SHA-256、MIME 与保存时间。复合外键限制所有权；每条记录每种类型只有一个检查点，表中不保存限时播放地址。

设置 `MEDIA_WORKER_ENABLED=true`、`PARSEVIDEO_ENABLED=true`、`OSS_ENABLED=true` 并配置服务后启用任务。状态依次为 `MEDIA_PENDING → MEDIA_SAVING → TRANSCRIPTION_PENDING`。使用 MySQL `FOR UPDATE SKIP LOCKED` 领取，到期租约可以恢复；写检查点、更新解析结果和推进阶段都检查任务 ID、用户、记录、租约身份、版本及有效期。网络下载、重新解析和 OSS 上传均在短数据库事务之外执行。

视频和封面逐个上传并立即提交检查点。视频已保存而封面失败时，重启或重试只处理封面。HTTP401/403 会在本次尝试内重新解析分享链接一次，持久化新地址并继续缺失资源；再次失效进入有上限的退避，避免重复使用旧地址或无限解析。永久错误直接停止；媒体尝试上限独立于解析阶段，状态 API 的 `attemptCount` 为跨阶段总次数。租约默认30分钟，配置需覆盖两轮下载和解析的预计时限；OSS 读取空闲超时不是整个上传的硬时限，任何超出租约的结果仍被拒绝入库。

没有封面地址时允许保存视频后等待转写；有封面时必须完成两个检查点才能推进。`TRANSCRIPTION_PENDING` 仅表示媒体已保存，听悟、摘要和知识索引仍待实现。素材长期保留，公开播放接口将在单独模块中实现。正常失败/结束清理暂存文件；进程突然中断可能遗留暂存文件或已上传但未提交的对象，自动回收尚未实现。旧租约不会自动删除内容寻址对象，以免误删新租约正在使用的同一对象。

MySQL 测试验证阶段原子性、断点恢复、来源刷新、有限重试、永久错误、并发领取、锁跳过、租约过期隔离、错误所有权与数据库回滚。设置 `MEDIA_WORKER_TEST_ENABLED=true`、`MEDIA_WORKER_TEST_PARSEVIDEO_BASE_URL`、OSS 配置与阿里凭据，再运行 `Server/scripts/Test-MySql.ps1` 可启用真实解析→下载→私有 OSS→MySQL 验证；使用公开 B 站样例，检查两个对象的私有 ACL、大小与 SHA-256 元数据，最后只删除本次独立测试命名空间中创建的对象。未启用时明确跳过，模拟适配器测试不能替代这个真实闭环。

2026-10-06 真实媒体任务11项验证全部通过，测试对象与暂存文件已清理；本轮同样仅对测试 JVM 使用当时公网 DNS 映射解决本机 Fake-IP，生产校验未放宽。独立的后端兼容性验证共89项，81项通过，8项真实外部调用按开关跳过；聊天真实验证与此前其他云适配器验证均单独记录，不将跳过项算作通过。

## 通义听悟离线转写适配器

使用官方 `com.aliyun:tingwu20230930:2.0.26` SDK。设置 `TINGWU_ENABLED=true`、`TINGWU_APP_KEY` 和共享阿里凭据后创建云客户端；固定通过 HTTPS 访问北京地域听悟 OpenAPI，OSS Bucket 可以继续位于深圳。`TINGWU_SOURCE_LANGUAGE` 默认 auto，也支持 cn/en/yue/ja/ko。只开启语音转写、全文摘要 Paragraph 和关键信息 KeyInformation，不开启翻译、PPT 抽取或推送回调。聊天模型仍是单独的 DeepSeek 配置。

`TingwuClient.create` 返回任务 ID，`get` 映射 ONGOING/COMPLETED/FAILED/INVALID，以及受限失败分类；不转发原始供应商错误消息。TaskKey 是自定义关联标识，官方协议未承诺其具有幂等性。SDK 自动重试关闭：创建请求超时、5xx 或无法确认任务 ID 时返回 `SUBMISSION_UNCERTAIN`，调用方须保留提交意图并核对结果，不能盲目重新创建付费任务。查询失败可以有限重试；永久鉴权/参数错误不能自动重试。

私有 OSS 为听悟生成专用媒体链接：`signedGetForTranscription` 仅允许当前用户/记录的视频键，有效期3～12小时；`TINGWU_SOURCE_URL_TTL` 默认4小时，以覆盖听悟的排队与下载窗口。该链接是后端内部能力，不作为客户端播放地址。普通播放签名仍限制在30秒～1小时。源文件长期保留不受签名到期影响。

`TingwuResultReader` 下载完成任务的转写、摘要与智能纪要 JSON，校验结果 TaskId。按 ParagraphId、SpeakerId 和 SentenceId 合并词项，保留起止毫秒；读取全文摘要、关键词与带时间戳重点。未生成的可选算法结果保留为空，不虚构摘要；听悟的场景分类不等于应用的知识主题分类。主题分类、知识入库和检索将在后续业务模块完成。

结果下载仅接受公网地域 OSS 域名，文档中 HTTP OSS 地址提升到 HTTPS，原始路径与签名查询保持不变。socket 使用经校验的公网 DNS 地址，不启用系统代理、重定向或自动重试；不附加阿里 Authorization 头。默认连接5秒、读取空闲30秒、每份结果的 HTTP 总时限60秒、结果上限16 MiB，可通过 `TINGWU_CONNECT_TIMEOUT`、`TINGWU_READ_TIMEOUT`、`TINGWU_RESULT_TIMEOUT`、`TINGWU_MAX_RESULT_BYTES` 调整。失效结果返回 `RESULT_EXPIRED`，调用方应重新查询任务获取链接，不重新转写源文件。SDK 原始日志与异常正文不暴露到 API。

真实验证默认跳过。设置 `TINGWU_TEST_ENABLED=true`、`TINGWU_TEST_MEDIA_PATH`（小于5 MiB的合成中文 WAV）、`TINGWU_TEST_CHECKPOINT_PATH`（忽略目录中的独立 JSON 文件）、AppKey、阿里凭据和 OSS 配置，再运行 `./mvnw.cmd "-Dtest=TingwuLiveIntegrationTest,TingwuContractTest,TingwuResultReaderTest,OssStorageContractTest" test`。首次上传合成语音并创建一个付费任务；检查点在提交前写入，后续超时运行复用任务 ID，每15秒查询一次，最多等待5分钟。提交结果不确定时保留检查点并拒绝自动重建；完成后清理本次测试源对象，不删除云端任务记录。

2026-10-06 已验证私有 OSS 输入→真实听悟创建/查询→原文、摘要、关键词与重点结果解析，30.585秒合成中文语音产生3段原文、10个关键词和1个重点；17项本模块及 OSS 兼容验证全部通过。本机 Fake-IP 环境仅对测试 JVM 注入实际 OSS 结果主机的当时公网 DNS 映射，生产限制未放宽。适配器单独不负责知识入库，业务接续见下节。

新增 SDK 后，完整后端兼容性检查共101项，92项通过、9项真实外部调用在该轮跳过，构建与打包成功；真实聊天、媒体保存和听悟验证均按各自开关单独执行，结果见各模块记录。

若本机 Maven 镜像尚未同步该 SDK，可通过 `mvnw -s <独立 settings.xml>` 临时选择 Maven Central；本轮使用忽略目录中的测试配置，未修改全局 Maven 设置，仓库依赖仍按标准 Maven Central 坐标声明。

协议依据：[离线转写及签名 URL 窗口](https://help.aliyun.com/zh/tingwu/offline-transcribe-of-audio-and-video-files)、[任务查询](https://help.aliyun.com/zh/tingwu/api-tingwu-2023-09-30-gettaskinfo)、[转写结构](https://help.aliyun.com/zh/tingwu/voice-transcription)、[摘要结构](https://help.aliyun.com/zh/tingwu/large-model-summary/)。

## 持久化转写任务与知识结果

V5 迁移为已有队列增加阶段独立的失败计数，并创建 `fragment_transcriptions`、`fragment_knowledge`、`fragment_sentences`、`fragment_key_points`。所有结果以记录和用户的联合外键隔离，保留原文顺序、说话人、句子 ID 与起止毫秒，以及全文摘要、关键词和重点；不保存输入或结果的签名 URL。删除用户时关联数据按已有外键级联删除，私有 OSS 文件的删除清理需要后续业务单独处理。

启用 `database` profile、`OSS_ENABLED=true`、`TINGWU_ENABLED=true`、`TRANSCRIPTION_WORKER_ENABLED=true` 后自动处理 `TRANSCRIPTION_PENDING`。前两段解析、保存媒体仍分别需要自己的 worker 开关。所有开关默认关闭，避免无凭据启动时产生云调用。

| 环境变量 | 默认值及作用 |
| --- | --- |
| TRANSCRIPTION_POLL_DELAY | 2s，本机队列扫描间隔 |
| TRANSCRIPTION_QUERY_DELAY | 1m，每个已创建云任务的正常查询间隔，允许15秒～10分钟 |
| TRANSCRIPTION_LEASE_DURATION | 5m，需覆盖云查询及三份结果的超时预算 |
| TRANSCRIPTION_MAX_TASK_AGE | 24h，从提交意图起计算的等待上限，允许1～72小时 |
| TRANSCRIPTION_MAX_FAILURES | 8，连续可恢复失败上限，正常 ONGOING 查询会重置 |
| TRANSCRIPTION_RETRY_BASE_DELAY | 10s，指数退避，最多1小时 |

这些默认值是保护线程和云调用的工程参数，不代表用户每天的投喂额度。任务领取用 `FOR UPDATE SKIP LOCKED`，外部调用在事务之外；版本、租约和所有权隔离失效执行器。创建前先提交关联 TaskKey 的意图，成功后保存唯一云 TaskId；进程重启后只查询该 ID。创建超时、未知接受结果、或响应之后数据库未能保存 ID 时进入 `FAILED / TINGWU_SUBMISSION_UNCERTAIN`，不自动再次 POST。仅确认的参数、鉴权、限流等拒绝可以清除未接受意图，按永久或有限重试处理。

若原执行器晚到的成功响应带回同一意图的 ID，允许恢复为 `TRANSCRIBING`；其余不确定状态需要人工在听悟核对对应 TaskKey，保留数据后再处理，不提供自动重建或管理 API。源链接失效、格式不支持、无语音与超过等待上限都有独立错误码。查询或结果下载可恢复失败复用原任务，`RESULT_EXPIRED` 下次重新 GET 获取结果链接。

完成转写后，摘要、关键词、原文和重点在同一事务中提交，任意一项入库失败全部回滚；随后进入 `KNOWLEDGE_PENDING`，交给下文知识增强模块处理。语义索引和面向用户的内容详情尚未完成，因此此状态还不能宣称知识已经可检索。媒体访问接口见下节。

`Server/scripts/Test-MySql.ps1` 验证并发领取、锁跳过、租约恢复、不确定提交、晚到响应、拒绝重试、查询预算、任务超时、来源隔离及结果整体回滚，也验证 V4 已有媒体任务原样升级到 V5。设置 `TRANSCRIPTION_WORKER_TEST_ENABLED=true`、`TINGWU_TEST_MEDIA_PATH`、`TINGWU_WORKER_TEST_CHECKPOINT_PATH` 和云凭据可开启真实 OSS→听悟 worker→MySQL 测试。独立检查点在付费 POST 前落盘，重跑复用已有任务；成功后清理本次唯一命名空间的源文件和检查点。该测试用小型合成 WAV 直接建立媒体检查点，解析和视频下载的真实验证见前文。

2026-10-06 真实转写业务及配置验证共14项，全部通过；实际摘要、关键词和带时间戳原文已写入隔离 MySQL。仅本次新建的云端测试对象已清理，已有 Bucket、用户数据库及凭据配置未修改。

新增业务模块及升级测试后，完整后端验证共116项，106项通过、10项真实外部调用按开关跳过，构建和打包成功；上面的真实转写业务验证单独执行并已通过。

## 本人媒体访问接口

`GET /api/v1/fragments/{id}/media?kind=VIDEO`，`kind` 默认 VIDEO，也支持 COVER；需要 Bearer 登录。返回 `kind`、HTTPS `url`、UTC `expiresAt`、`sizeBytes` 和 `contentType`，响应 `Cache-Control: no-store`。视频和封面在媒体阶段保存完成后即可访问，不必等到转写或索引完成。服务先按登录用户、记录和媒体类型查持久化检查点，再签名，忽略客户端的 userId/ttl 等额外参数；不允许指定 Bucket、对象键或资源地址。

无登录返回401；其他用户、不存在或尚未保存的媒体统一返回404 `MEDIA_NOT_FOUND`；错误类型返回400 `INVALID_MEDIA_KIND`；OSS 未配置、Bucket 不匹配或供应商失败返回503 `MEDIA_UNAVAILABLE`，不暴露供应商正文。普通链接默认5分钟，由 `OSS_SIGNED_URL_TTL` 控制且限制30秒～1小时，不使用听悟的长时输入链接。客户端按返回的过期时间重新获取签名地址，播放器可直接向 OSS 发 Range 请求。已发出的签名地址在其到期前仍可用，注销不即时撤回它。

签名生成依据数据库检查点，并不对每次访问额外发 HEAD 检查对象；云端对象被手动移除时，播放器应处理 OSS 的失败响应。这里只实现后端媒体授权和传输，Android 播放器尚未完成。

运行 `Server/scripts/Test-MySql.ps1` 验证媒体所有权、错误映射、默认期限、拒绝客户端 TTL、禁用签名器及原状态接口兼容性。设置 `PLAYBACK_TEST_ENABLED=true`、`PLAYBACK_TEST_MEDIA_PATH`（16字节～5 MiB的合成 WAV）、OSS 配置和阿里凭据，可开启真实 HTTP 登录→媒体 API→私有 OSS 验证；使用本轮唯一用户命名空间，只清理本次上传的对象。测试检查5分钟 V4签名、Range 206、原始字节一致、无签名403及其他用户404；合成音频验证字节传输，不代替 Android 视频解码验收。

2026-10-06 媒体接口及状态接口兼容验证共8项全部通过，含真实私有 OSS 访问；本次上传对象已清理，构建和打包成功。

## DeepSeek 知识增强

V6 为知识表增加增强摘要、要点、主题分类、模型名称及完成时间；原始听悟摘要、关键词和带时间戳原文保持独立。设置 `AI_CHAT_ENABLED=true`、聊天配置和 `KNOWLEDGE_ENRICHMENT_ENABLED=true` 后处理 `KNOWLEDGE_PENDING`，完成进入 `INDEX_PENDING`。默认扫描2秒、租约5分钟、阶段独立上限3次、指数退避基数10秒，分别由 `KNOWLEDGE_POLL_DELAY`、`KNOWLEDGE_LEASE_DURATION`、`KNOWLEDGE_MAX_ATTEMPTS`、`KNOWLEDGE_RETRY_BASE_DELAY` 配置。租约需覆盖聊天超时并留15秒余量；未启用时不加载云工作线程。

首版采用8个主题枚举：LEARNING（学习）、TECHNOLOGY（科技）、LIFESTYLE（生活）、HEALTH（健康）、FINANCE（财经）、ART（艺术）、ENTERTAINMENT（娱乐）、OTHER（其他）。每条资料选择1～3个，增强摘要不超过2000字符、要点1～8条且每条不超过300字符。这是可调整的首版分类与展示方案。

模型输入为引用资料 JSON：标题最多500个 Unicode 字符、作者100、备注1000、听悟摘要4000、关键词1000，以及前8段原文各500。限制针对单次模型成本与输入长度，数据库中的原文不会截断；较长视频的分类主要依赖听悟摘要和关键词，开头原文样本不代表逐句完整分析。模型不接收媒体签名地址，也没有注册工具。系统提示把转写中的角色声明和指令作为正文，输出经过严格 JSON、字段、类型、重复字段、分类和长度检查，不接受 Markdown、未知字段或尾随内容；这不能保证模型的总结判断总是正确，原文仍是核对依据。

任务使用数据库锁、版本与租约隔离执行器，AI 调用在事务之外，增强字段和阶段推进在同一事务中完成。模型失败、格式错误或数据库回滚进行阶段独立的有限重试，耗尽后保留原始知识并记录稳定错误码；不暴露供应商正文。聊天 SDK 不在业务重试之外叠加同步重试。进程在模型响应后、结果提交前中断时可能再次请求归纳并产生少量重复 token 费用，阶段上限约束重复调用。

运行 `Server/scripts/Test-MySql.ps1` 验证格式约束、无工具输入、原文保留、所有权、锁跳过、失效租约、重试预算、数据库回滚，以及 V5→V6 保留已有知识升级。设置 `ENRICHMENT_TEST_ENABLED=true` 并从本机环境加载 `AI_CHAT_API_KEY`、`AI_CHAT_MODEL`，可启用一次真实模型归纳入库测试；仅发送合成数学资料，不记录密钥、供应商正文或个人资料。

2026-10-06 模块与数据库验证15项全部通过，含真实 DeepSeek→知识增强→MySQL；该阶段进入 `INDEX_PENDING`，随后由下节本地索引模块接续。

完整后端兼容性验证共132项，120项通过、12项真实外部调用按开关跳过，构建和打包成功；本模块的真实调用按独立开关单独验证，不将跳过算作通过。

## 持久化本地语义索引

V7 创建 `fragment_indexes` 和 `fragment_index_chunks`，记录用户、模型版本、分段版本、512维、片段数和完成时间。每段保存原文及标准小端 float32 向量，共2048字节；入库前检查有限数与单位范数，数据库约束检查向量长度、所有权和时间范围。删除用户或记录时，数据库索引级联删除。MySQL 8.0 不需要向量插件，后续检索将按当前用户读取候选向量计算相似度。

`database` profile 下开启 `KNOWLEDGE_INDEX_ENABLED=true`，处理 `INDEX_PENDING`→`INDEXING`→`READY`。使用已有 LangChain4j 中文 BGE ONNX 模型，没有远程 embedding 供应商或 API Key。索引开启本身不调用 DeepSeek；上游增强阶段仍需单独启用。模型延迟到实际处理时加载，空队列不加载 ONNX。

| 环境变量 | 默认值及作用 |
| --- | --- |
| INDEX_POLL_DELAY | 2s，队列扫描间隔 |
| INDEX_LEASE_DURATION | 10m，可配1分钟～1小时；每16段推理前与提交前续租 |
| INDEX_MAX_ATTEMPTS | 3，阶段独立预算，不受解析/下载等总尝试数影响 |
| INDEX_RETRY_BASE_DELAY | 10s，指数退避，最多1小时 |
| INDEX_MAX_CHUNKS | 10000，可配16～50000；单个视频索引的资源保护上限 |

索引覆盖标题、作者、备注、原始及增强摘要、关键词、增强要点、完整转写和带时间戳重点。原文与重点按64行分页读取，Unicode 窗口为384个字符、相邻重叠48个字符，不切断 emoji 等补充字符。超出资源上限明确失败为 `INDEX_TOO_LARGE`，不会静默截断或删减原始知识。单个长句拆成多段时，每段沿用原句完整时间范围，不虚构段内精确时间。不同来源类型分别保留，摘要没有伪造时间戳。

任务用锁跳过、版本与租约隔离；ONNX 推理在数据库事务外进行，最多16段一批，每批前续租，过期执行器丢弃结果。模型初始化或单批推理若超过租约，会由下次领取恢复，不让旧执行器写入。全部推理完成后，按64段批量写入，索引整体替换与 `READY` 推进在同一事务中提交；失败回滚保留旧索引。进程中断后会重算本地向量，不触发重复云转写或聊天费用。当前按单个视频在内存构建索引，50000段仅向量就约100 MiB，调高上限需要相应的 JVM 内存和数据库事务预算；这些限制是工程参数，不是用户的每日额度。

通过 `Server/scripts/Test-MySql.ps1` 验证 Unicode 全文覆盖、向量二进制往返、真实模型入库、130段原文分页、时间归属、用户隔离、并发领取、续租与失效执行器、阶段预算、整体回滚、资源上限和 V6→V7 升级。真实 ONNX 测试默认执行，无外部凭据或云费用。当前模块完成索引数据与后台处理；搜索 API 见下节，AI 数据库工具将在独立模块实现。

2026-10-06 索引模块、迁移及模型验证共20项通过；真实 ONNX 向量保存到 MySQL 后，“变化率”查询对导数资料的相似度0.5127，高于烹饪资料0.3570。这是合成中文样例的功能验收，不代表全平台内容上的固定召回准确率。完整后端兼容性检查共145项，133项通过、12项真实外部调用按开关跳过，构建与打包成功。

## 本人历史语义搜索 API

`POST /api/v1/fragments/search`，需要 Bearer 登录，JSON 示例：

```json
{
  "query": "找之前保存的函数瞬时变化率学习资料",
  "fromDate": "2026-10-01",
  "toDate": "2026-10-06",
  "category": "LEARNING",
  "author": "老师",
  "keyword": "导数",
  "limit": 10
}
```

只有 `query` 必填，最多256个 Unicode 字符；`limit` 默认10，可配1～20。其他字段可省略或为 null；日期按资料投喂时记录的业务日期筛选，起止都包含在内，支持1000～9999年且起日不能晚于止日。分类为知识增强的8个主题之一，作者为最多100字符的子串；关键词最多64字符，在完整标题、作者、备注、摘要、关键词、要点和原文中匹配，不受向量分段边界影响。关键词和作者用绑定参数与 `LOCATE`，`%`、`_` 作为普通文字。控制字符、空白查询及超长字段拒绝为400 `INVALID_SEARCH`；非法 JSON/日期/分类为400 `INVALID_JSON`。

数据库先按登录用户、筛选条件、`READY`、当前模型和分段版本限定候选；客户端附加 userId 不影响所有权。256段一页，用记录 ID 和段序号做键集分页，全部计算归一化向量点积，按每个视频的最佳片段保留一个结果。首版混合策略为语义相似度召回，加完整查询文字在片段内命中的补充召回和分数提升；不做中文分词、BM25 或远程模型重排。默认相似度阈值0.40、字面提升0.10，分别由 `SEARCH_MIN_SIMILARITY`（0～1）、`SEARCH_LITERAL_BOOST`（0～0.30）调整。分数相同按资料 ID 排序，片段相同分数保留较早段。阈值与权重是样例验证后的工程起点，不是置信概率或固定准确率；无命中返回空数组。

成功返回 `items` 和本人范围内的 `scannedChunks`，`Cache-Control: no-store`。每个 item 包含 `fragmentId`、标题、作者、投喂日期、最多2000字摘要、主题分类、排名分数 `score`、原始语义分数 `semanticScore`、是否字面命中，以及匹配原文和原句时间范围。`videoMediaPath` / `coverMediaPath` 在相应私有媒体检查点存在时返回授权媒体 API 的相对路径；客户端再带登录获取短时 URL，不直接提供平台临时地址、OSS 对象键或向量。摘要、标题和作者的卡片展示长度分别为2000、500、100，数据库原始内容保持完整。

默认每次最多扫描100000段（`SEARCH_MAX_CHUNKS`，100～1000000），超过返回503 `SEARCH_SCOPE_TOO_LARGE`，要求缩小日期或筛选；不将截断候选冒充完整结果。模型推理、数据库访问均无长事务，分页内存与20项结果堆有界。每个应用实例同时处理一个搜索，忙时429 `SEARCH_BUSY`，避免在本地模型前排无界队列。`SEARCH_TIMEOUT` 默认15秒，可配1～60秒，在数据库调用及推理返回后、分页和最终响应前检查；它是处理预算，不能强制中断正在进行的 JDBC 或 ONNX 调用。损坏向量/分类返回503 `SEARCH_INDEX_INVALID`，其他内部失败返回503 `SEARCH_UNAVAILABLE`，不暴露原始异常正文。

搜索不锁住历史数据；并发投喂、索引更新或删除期间，结果是本次逐页读取的资料视图，卡片组装会再次验证用户、状态和筛选。变化中的记录可能被省略，重新查询可看到新状态。目前没有自动重建或管理 API；损坏索引需通过后续运维模块恢复。

运行 `Server/scripts/Test-MySql.ps1` 验证真实 HTTP 注册登录→MySQL→ONNX→搜索卡片，包含“变化率→导数”、跨用户隔离、日期/分类/作者/字面筛选、跨分段关键词、271段键集分页、视频去重、模型版本/状态隔离、并发限制、输入边界及损坏向量。媒体卡片的本轮夹具不上传云文件；真实私有 OSS 签名与 Range 验证见媒体接口章节。分类 SQL 的语义参考 [MySQL JSON 搜索函数](https://dev.mysql.com/doc/refman/8.0/en/json-search-functions.html)。

2026-10-06 搜索模块12项验证全部通过，含真实 HTTP、隔离 MySQL 与本地 ONNX 的中文语义召回。完整后端兼容性检查共157项，145项通过、12项真实外部调用按开关跳过，构建与打包成功；本轮没有重新请求聊天或听悟云服务。

## 本人内容详情 API

`GET /api/v1/fragments/{id}/content`，需要 Bearer 登录，返回 `Cache-Control: no-store`。包含投喂链接与备注、来源平台、业务日期/时区、UTC 创建时间、当前处理阶段和稳定错误码，以及标题、作者、受保护的媒体 API 路径、原文句数和带时间戳重点数。未解析时标题为“投喂记录”；媒体检查点尚不存在时相应路径为 null。

`knowledge` 在尚未转写时为 null；已完成转写的记录无论是否增强/索引成功，都能读取已保留资料。它包含毫秒时长、`originalSummaryPreview`、增强 `summary`、增强 `points`、听悟 `keywords`、主题 `categories` 和 UTC 完成/增强时间。增强尚未产生时，summary/enrichedAt 为 null，points/categories 为空；不根据原文虚构摘要或把失败伪装为完成。所有内容查询都带登录用户，附加 userId 无效；不存在与其他用户统一404 `FRAGMENT_NOT_FOUND`，无登录401。

为了让详情首屏响应有界，标题最多500个 Unicode 字符、作者100、原始摘要预览4000、增强摘要2000、增强要点最多8条/每条300、关键词最多100条/每条100。分别返回 `titleTruncated`、`authorTruncated`、`originalSummaryTruncated`、`summaryTruncated`、`pointsTruncated`、`keywordsTruncated`；数据库原始值保持完整。正常增强结果已满足这些展示边界。完整转写与带时间戳重点通过下节分页接口读取，不在详情首屏一次性加载。客户端应显示预览提示，而不能将标记为截断的字段宣称为全文。

详情和知识读取在只读 REPEATABLE_READ 事务中完成，保持此次响应的处理阶段与资料视图一致；没有模型或云资源调用。数据库 JSON 类型/分类异常返回503 `CONTENT_INVALID`，不回显原始内容或异常正文。API 不返回平台临时资源地址、OSS 对象键、云 TaskId、向量或租约信息。

2026-10-06 详情模块5项真实 HTTP/MySQL 验证全部通过，覆盖已完成与处理中/失败资料、预览边界、补充 Unicode 字符、原文保留、所有权及异常分类；同轮回归状态接口3项和真实语义搜索6项，共14项通过，构建与打包成功。媒体路径测试使用数据库夹具；私有 OSS 与上游转写真实验证见前文各模块记录。

## 带时间戳全文分页 API

`GET /api/v1/fragments/{id}/transcript?kind=SENTENCE&ordinal=0&offset=0&limit=10`，需要 Bearer 登录，响应 `Cache-Control: no-store`。`kind` 默认 SENTENCE（完整转写），也支持 KEY_POINT（听悟带时间戳重点）；两种数据各自按原始 ordinal 排序。ordinal 默认0，offset 默认0且按该行的 Unicode 字符计数，limit 默认10、范围1～20。所有游标参数为整数，非负且有明确上限；不存在的未来 ordinal 且 offset=0 表示读完，而悬空、越界或恰好行尾的非零 offset 返回400 `INVALID_TRANSCRIPT`，类型无效也返回相同错误。

返回 `fragmentId`、kind、`transcriptionAvailable`、items 和 `nextCursor`。尚未转写时 available=false、items 为空；已生成但无对应句子/重点或读取到末尾时 available=true、items 为空。nextCursor=null 表示本视图已结束；否则将其 ordinal 和 offset 原样传入下一次请求，并保留相同 kind。处理失败也允许读取已入库的原文。不存在或其他用户统一404 `FRAGMENT_NOT_FOUND`，附加 userId 不能改变查询所有权。

每个 item 为一个最多1000个 Unicode 字符的文本窗口，包含原行 ordinal、当前 offset、是否 continuation、原始 sentenceId、startMs/endMs、text，以及句子的 speakerId。超过1000字的长句分多页完整读取，无重叠、无截断，补充 Unicode 字符不会被拆开；将同一 ordinal 的 text 按 offset 拼接即可还原全文。窗口沿用整句或重点的原始时间范围，不估算句内定位。speakerId 最多128字符，超过时 `speakerTruncated=true`，数据库原始标识不变；KEY_POINT 不产生说话人。

数据库直接用 `SUBSTRING` 读取有界窗口，不把每个长句全文先加载到服务端内存；单页最多21次窗口读取，限制往返与响应大小。这些限制保护单次请求资源，不限制总页数或用户可读取的全文长度。每页在只读 REPEATABLE_READ 事务内保持一致视图；跨页删除会返回404，人工修改原文可能使旧 offset 失效，需重新读取。当前没有内容编辑 API。

2026-10-06 分页模块5项真实 HTTP/MySQL 验证全部通过，包含4001个 emoji 的无损跨页拼回、序号间隙、空原文、长重点、时间与 sentenceId 保留、末尾和整数上界、输入边界及跨用户拒绝；同轮回归详情5项和状态3项，共13项通过，构建与打包成功。本轮复用已入库的数据夹具，没有新云调用。

## 已注册的历史检索工具

`HistoryToolFactory.bind(CurrentUser)` 为每次已鉴权对话创建独立的 `HistorySearchTool`，通过 LangChain4j `@Tool` 注册只读的 `findSavedKnowledge`。模型参数仅包含 query、fromDate、toDate、category、author、keyword；用户归属由服务端绑定，不接受模型传入 userId、SQL、表名或媒体地址。日期、分类、作者与字面关键词是可选硬筛选，提示模型仅在用户明确要求时填写，避免用猜测的关键词破坏语义召回。

工具执行使用已实现的本人搜索服务，每次最多5条资料，每轮最多3次尝试，非法或未知工具调用同样计入预算。工具参数严格检查类型、重复字段、未知字段和尾随 JSON；供应商与数据库错误仅返回稳定错误码。模型收到标题200字、摘要400字及匹配原文预览，没有媒体签名或向量。完整来源卡片另保存在该轮服务端注册表，供后续对话 API 返回；不把模型生成的卡片当作已验证来源。新用户或新一轮工具实例不共享注册表。

运行 `Server/scripts/Test-MySql.ps1` 检查注册参数、调用预算、真实 MySQL/ONNX 召回、无结果与跨用户拒绝。设置 `HISTORY_TOOL_TEST_ENABLED=true` 并从本机环境加载 `AI_CHAT_API_KEY`、`AI_CHAT_MODEL`，可启用真实 DeepSeek 工具调用→本人数据库检索→模型续答测试，仅使用本轮合成资料。2026-10-06 共13项验证全部通过，包括上述真实模型往返及搜索服务回归，构建与打包成功。本模块尚未提供对话 HTTP 接口或持久化聊天上下文。

## 创建持久化对话会话

`POST /api/v1/chat/sessions` 需要 Bearer 登录和 UUID 格式的 `Idempotency-Key`，请求体为 `{"title":"数学学习"}`，可省略 title，空白标题使用“新对话”。标题最多100个 Unicode 字符，拒绝控制字符和未配对的代理字符。创建返回201，同用户同键同标题重放返回200，`replayed` 标明重放；同键不同标题返回409 `IDEMPOTENCY_CONFLICT`，不产生第二条会话。标题先去除首尾空白，UUID 大小写归一化；不同键允许创建标题相同的不同会话。

响应包含 sessionId、title、createdAt、updatedAt、replayed，时间为 UTC，`Cache-Control: no-store`，Location 为会话相对路径。归属取自登录主体，请求附加 userId 无效。V8 创建 `chat_sessions`，按用户和幂等键唯一，外键随用户删除级联；按用户行锁串行处理创建，不在事务中调用 AI。创建会话无需启用模型，因此断开外部模型配置也可以保存会话。当前标题保持创建值，消息、会话读取和流式回答在后续模块接入。

2026-10-06 创建模块5项真实 HTTP/MySQL 验证和4项数据库迁移、4项历史工具回归共13项全部通过，包括并发重放、跨用户同键、输入边界、用户级联删除、空库及 V7→V8 保留已有资料升级。

完整后端回归179项，166项通过、13项外部真实调用按开关跳过，构建与打包成功。随着独立测试上下文增加，默认连接池累计触及测试 MySQL 的连接上限；首轮测试配置的 minimum-idle=0 使回归通过，但随后直接检查 DataSource 发现最大连接数仍被 database profile 覆盖为10。测试限制已移到 `src/test/resources/application-database.properties`，并增加实际连接池参数检查，确保最大4个连接、无空闲连接预留；生产连接池配置保持原值。

连接池修复的独立验证共18项全部通过，包括真实 DataSource 参数、MySQL 升级、并发会话和流式编排边界。扩大回归时另发现可选真实流式测试提前初始化模型的问题；其开关已单独提升到测试类级别，禁用时整个 Spring 测试上下文不会加载模型。清除 AI 密钥与模型配置后完整回归189项，175项通过、14项外部真实调用按开关跳过，构建与打包成功。

## 流式对话与历史工具编排

`ChatTurnEngine.stream` 接收鉴权主体、服务端加载的已完成问答、当前问题、单轮取消标志和事件监听器；尚未对外提供 HTTP 消息接口。每轮创建独立的本人历史工具，从已完成问答中选择最近最多8轮、问答合计最多24000个 Unicode 字符作为历史上下文，保留整轮且从旧到新排列；`contextTruncated` 明示较早上下文未进入本次请求。当前问题最多2000个 Unicode 字符，允许换行 LF 和制表符，拒绝其他控制字符与未配对代理字符。历史仅用于理解对话，查找个人资料仍提示模型重新调用工具，不把旧答案当成已验证知识。

使用真实供应商 `StreamingChatModel` 的增量回调，最多4次模型请求、累计3次历史工具调用；额度用尽后的最后一次请求不注册工具。保留当前模型组装的 AiMessage（包括 DeepSeek 续请求需要的 reasoning），不把推理内容或工具参数发送给监听器。工具前的文字可能是过渡说明，`roundStarted` / `delta` / `roundEnded(intermediate)` 区分轮次；只有 intermediate=false 的最终正文可作为完成回答。最终来源卡片只取该轮服务端注册表；正文正式 `[资料N]` 引用必须命中这些来源，否则 `CHAT_CITATION_INVALID`，不完成本轮。普通文字仍是模型输出，不能保证所有事实正确，也不应根据正文任意网址或编号自动生成卡片。

回调只向有界队列投递，工具查询与监听器发送都在调用线程执行，避免占用模型 I/O 回调做数据库工作。每次响应最多24000个 UTF-16 单元、推理64000、工具参数合计49152，队列128个增量；积压返回 `CHAT_STREAM_BACKPRESSURE`，不会静默丢字。默认每实例最多2轮并发（`CHAT_MAX_CONCURRENT`，1～8），繁忙立即429 `CHAT_BUSY`，无排队。整轮预算默认180秒（`CHAT_TURN_TIMEOUT`，1秒～10分钟）；正在进行的 JDBC/ONNX 查询不能被强制中断，返回后检查预算。取消、超时、监听器投递失败和供应商失败都有稳定错误码，不自动重试付费请求，不保留供应商错误正文。

取消优先使用 [LangChain4j 官方流式取消机制](https://docs.langchain4j.dev/tutorials/response-streaming/#streaming-cancellation)，捕获句子、推理或工具回调中的 StreamingHandle 并关闭传输；首次回调前尚未取得句柄时，迟到回调会立即取消并被忽略。释放本轮容量不能证明云端已停止计费，云调用也受已有 SDK 超时限制。模型正在生成的草稿不能作为成功入库回答；持久化状态、断线恢复和 SSE 投递由后续模块接入。

2026-10-06 编排单元9项、真实 DeepSeek/MySQL/ONNX 两轮对话1项、创建会话5项和聊天供应商契约6项，共21项全部通过，构建与打包成功。真实验证检查前一轮问答进入第二轮、本人检索工具调用、来源归属、真实增量与最终正文一致，以及封面/视频授权路径；媒体检查点使用本轮数据库夹具，没有新增 OSS 上传或 Android 播放验证。设置 `CHAT_TURN_TEST_ENABLED=true` 并加载本机聊天环境变量后运行 `Server/scripts/Test-MySql.ps1` 可重跑这项付费验证。

真实测试开关提升到类级别后，开启开关的真实 DeepSeek 两轮流式测试也单独重跑通过；禁用开关的无密钥完整回归见前节记录。

## 持久化问答与来源

V9 增加 `chat_turns` 和 `chat_turn_sources`，保存当前问题、RUNNING/COMPLETED/FAILED 状态、最终答案、稳定错误码、UTC 时间、上下文截断标记和模型/工具轮数。来源按用户与资料的组合外键约束，每轮最多15张，经数据库归属检查后保存卡片快照，包含摘要、匹配原文时间与保护媒体路径；不保存媒体签名、云 TaskId、模型推理或供应商错误正文。删除用户/会话会级联删除问答与来源，删除资料会移除对应来源卡片；既有答案正文保留，客户端不应把没有卡片的旧编号变成有效链接。

`ChatTurnStore.begin` 在用户→会话的固定锁顺序下创建单轮租约，问题规范化复用流式引擎的输入检查。UUID 幂等键以会话为范围，同键同问题返回已有轮次而不重新领取生成；同键不同问题409 `IDEMPOTENCY_CONFLICT`。同一会话存在有效 RUNNING 时，新键返回409 `CHAT_SESSION_BUSY`，不同会话可以各有一轮。只有 fresh=true 的调用者可以发起模型请求。完成/失败状态不自动重新调用模型；需要重试时使用新键明确创建新轮次。

租约为整轮配置预算加至少30秒余量，使用数据库 UTC 时间。读状态或创建下一轮时把已过期 RUNNING 标为 FAILED/`CHAT_INTERRUPTED`，进程恢复不会自动重试未知云调用。提交须匹配用户、会话、原租约标识、有效期限和 RUNNING 状态；过期或已结束的执行器不能写入。完成时重新核对账号启用状态和令牌版本，注销后的晚到答案不能提交。固定锁顺序也用于失败与状态读取；模型与推理从不在这些事务中执行。

答案与全部卡片在同一事务中提交；任何一张来源不属于本人、已不再 READY、序号/状态不合法或写入失败，整笔回滚，保持未完成状态供调用层明确标记失败。快照单张最多32768字节，仅接受该资料对应的媒体授权路径；读取时核对 JSON 中的资料 ID 与关系外键，异常返回503 `CHAT_STORAGE_INVALID`。最近上下文按会话、用户和当前轮次限定，只加载最近9条已完成问答并按时间顺序提供给引擎；失败和运行中的草稿不参与上下文，较早问答仍保留在数据库。

本模块提供可独立验证的存储业务方法，还没有 HTTP 消息读写或 SSE 接口。上层需要先持久化问题，再调用已验证引擎，最后提交结果；不能提前把流式草稿宣称为完成回答。

2026-10-06 持久化7项真实 MySQL 验证全部通过，包括规范化幂等、并发重放、有效生成互斥、过期恢复与迟到拒绝、仅已完成上下文、账号令牌版本变化、外键级联、来源写入整笔回滚、错误媒体路径、状态 CHECK 的 NULL 边界及快照 ID 一致性。完整后端回归196项，182项通过、14项真实外部调用按开关跳过，构建与打包成功；其中4项迁移验证覆盖空库和 V8→V9 保留旧会话升级。本轮没有新增云调用，不把数据库夹具当作完整 HTTP/云/播放闭环验收。

## 读取持久化消息 API

`GET /api/v1/chat/sessions/{sessionId}/messages/{turnId}` 需要 Bearer 登录，返回 `Cache-Control: no-store`。响应为 turnId、sessionId、question、state、answer、errorCode、createdAt、completedAt、contextTruncated、modelRounds、toolCalls 和经关系外键核对的 cards；时间为 UTC。RUNNING/FAILED 的 answer 为空，不返回草稿；COMPLETED 的最终答案和来源可在重连后恢复。读取发现过期 RUNNING 时将其持久化标为 FAILED/`CHAT_INTERRUPTED`，不会发起模型请求。

编号必须为正的有符号64位整数，非法编号400 `INVALID_CHAT_ID`；其他用户或不存在的会话统一404 `CHAT_SESSION_NOT_FOUND`，本人会话中其他会话的消息或不存在的消息统一404 `CHAT_TURN_NOT_FOUND`。附加 userId 不改变归属，未登录或已注销令牌返回401。响应不包含租约标识、过期时间、令牌版本、幂等键、推理内容或云内部信息。来源里的视频/封面路径需再带登录调用媒体授权接口。

2026-10-06 读取接口4项真实 HTTP/MySQL 验证、问答存储7项和会话创建5项，共16项全部通过，构建与打包成功。覆盖已完成正文及来源时间、处理中与过期恢复、用户/会话双重隔离、输入边界和真实注销后的401；本轮未调用模型或云媒体。发送消息与 SSE 投递仍待下一模块接入。

## 发送消息与 SSE API

`POST /api/v1/chat/sessions/{sessionId}/messages` 需要 Bearer 登录、UUID `Idempotency-Key` 和 JSON `{"message":"请找一下我保存的导数学习资料"}`。问题最多2000个 Unicode 字符，允许 LF 和制表符；上下文从数据库加载，请求中的 userId、roles 或 history 不参与模型输入。成功响应为200 `text/event-stream`、`Cache-Control: no-store`、`X-Accel-Buffering: no`。连接建立前的输入、所有权和容量错误返回 JSON，即使请求 Accept 仅指定 SSE；客户端需先检查 HTTP 状态和 Content-Type。

事件包含递增的 `id: turnId:sequence`，data 是 JSON：

| event | data 与客户端含义 |
| --- | --- |
| accepted | turnId、sessionId、state、replayed；先保存问题和租约，再开始生成 |
| round_start | round；开始一个模型响应轮次 |
| delta | round、text；按轮次追加草稿，文字含换行时仍使用 JSON 编码 |
| round_end | round、intermediate；true 表示工具前过渡说明，false 表示最终正文轮次 |
| heartbeat | turnId；模型静默期间约每3秒检测连接，不包含推理内容 |
| done | 完整消息快照；答案及来源已原子提交，可读取/恢复 |
| pending / failed | 同键重放运行中/失败的消息快照，不重新调用模型 |
| error | 稳定 code、message；读取消息接口确认持久化状态 |

最终卡片只来自服务端检索注册表，视频/封面路径需要登录后再获取媒体地址；推理和工具参数不会进入 SSE。客户端不能把流式草稿或 round_end 当作保存成功，只有 done 表示可恢复的完成状态。完成提交后发生断线仍保留结果，可通过读取接口或同键重连恢复。

同键同问题返回 accepted 后仅发送 done、pending 或 failed，随后关闭连接；同键不同问题409，另一键遇到同会话生成中409。不支持 Last-Event-ID 增量补发；重连恢复的是整条已保存消息。重新尝试失败问题必须明确使用新键，不自动重试付费模型请求。进程中断或数据库不可用时，原 RUNNING 租约会由下一次读取/创建请求过期处理。

每实例使用与 `CHAT_MAX_CONCURRENT` 相同大小的专用线程池，无排队；满载429 `CHAT_BUSY`，已经创建的新轮次标为 FAILED。SSE 连接预算为整轮预算加15秒；生成期间断线会取消传输并终止本轮。心跳不能强制中断 JDBC/ONNX，也不能保证已发送的云请求立即停止计费。异步请求沿用现有鉴权配置，未添加全局 ASYNC 放行。

2026-10-06 模块验证共25项全部通过，包括4项真实 HTTP/MySQL SSE 契约、1项真实 DeepSeek/MySQL/本地 ONNX 两轮 HTTP 对话、9项编排、7项存储和4项读取回归。覆盖真实增量、提交后完成事件、同键重连无重复调用、失败重放、安静期间断线取消、鉴权与归属，以及来源卡片。设置 `CHAT_API_TEST_ENABLED=true` 并加载本机聊天环境变量，运行 `Server/scripts/Test-MySql.ps1` 可重跑这项付费验证。媒体检查点使用数据库夹具，本轮未重新上传 OSS 或验证 Android 播放。

完整无密钥后端回归205项，190项通过、15项可选真实外部调用按开关跳过，构建与打包成功。

## 本人会话列表 API

`GET /api/v1/chat/sessions?limit=20&cursor=...` 需要 Bearer 登录，返回 `Cache-Control: no-store`。limit 默认20、范围1～50；cursor 首次省略，后续原样使用响应的 nextCursor。返回 items（sessionId、title、UTC createdAt/updatedAt）与 nextCursor；空列表/末页的 nextCursor 为 null。只读取登录用户的会话，不调用模型，也不返回幂等键、用户编号、问题正文或租约。

按 updatedAt 降序、sessionId 降序做键集分页，查询最多 limit+1 条；已有用户/活动时间/编号组合索引支持排序。游标为最多96字符的规范 Base64URL，限定有效 UTC 毫秒时间和正64位编号；非法游标或页大小400 `INVALID_CHAT_PAGE`。游标仅用于筛选位置，不能改变登录归属，即使传入其他用户的游标或附加 userId。

边界会话被删除时仍可继续分页，无需重新查询该记录。跨页不是固定快照：其他会话的新活动可能将记录移动到已读位置以上，这时需从第一页刷新；不会把分页结果宣称为变化期间的完整快照。发送、完成或失败一轮消息会更新会话活动时间。当前未提供会话改名、删除或详情 API。

2026-10-06 会话列表4项真实 HTTP/MySQL 验证、创建5项与 SSE 契约4项共13项全部通过，构建与打包成功。包含63条同毫秒排序和跨页无重复、默认/最大页大小、Unicode 标题、UTC 时间、边界删除、活动变动、游标规范与整数上界、跨用户游标及注销后401。本轮未请求外部模型或云服务。

## 本人消息分页 API

`GET /api/v1/chat/sessions/{sessionId}/messages?limit=10&before=...` 需要 Bearer 登录，返回 `Cache-Control: no-store`。limit 默认10、范围1～20；首次省略 before，后续使用响应的 nextBefore，必须为正64位消息编号。非法分页400 `INVALID_CHAT_PAGE`，非法会话编号400 `INVALID_CHAT_ID`，其他用户或不存在的父会话统一404 `CHAT_SESSION_NOT_FOUND`。

返回 items 与 nextBefore，按 turnId 降序读取最新问答，下一页只读取小于 before 的旧记录；nextBefore=null 表示本视图已结束。每项是与单条读取接口相同的消息快照，包含完整问题、已保存最终答案、状态、UTC 时间和核对过归属的来源卡片，不受模型最近8轮上下文限制。客户端可将已加载记录按 turnId 升序显示对话。RUNNING/FAILED 不返回草稿；分页读取会将已过期 RUNNING 持久化为 FAILED/`CHAT_INTERRUPTED`，不调用模型。

V10 只追加 `(session_id,user_id,id)` 分页索引，不修改已交付迁移。每页查询最多 limit+1 条，每条最多15张已保存来源，使用现有快照校验；损坏来源使整页返回503 `CHAT_STORAGE_INVALID`。用户→会话的固定锁顺序保证本页状态与来源一致，事务内不调用模型/云资源。跨页删除不影响位置，新增的更高编号不会进入旧页；变化中的 RUNNING 状态需重新读取或刷新最新页。响应不包含租约、用户编号、幂等键、推理或对象键。

2026-10-06 分页模块5项真实 HTTP/MySQL 验证、单条读取4项、持久化7项、迁移4项和 SSE 契约4项共24项全部通过，构建与打包成功。包含23轮历史跨页恢复、24000个 UTF-16 单元的 emoji 回答无损、来源时间、边界删除与新消息隔离、失败/运行中/过期状态、外来会话及分页参数、注销后401、损坏卡片拒绝，以及 V9→V10 保留旧问答与索引顺序。未请求外部模型或云服务。

## 本人历史日历 API

`GET /api/v1/calendar?month=2026-10` 需要 Bearer 登录，返回 `Cache-Control: no-store`。month 必填，为1000～9999年范围的 YYYY-MM；缺失、非法月份或非规范格式400 `INVALID_CALENDAR`。响应包含 month、整月 total 和按日期升序的 days，覆盖该月全部28～31天，没有记录的日期计数为0。

每天提供 date、total、ready、processing、failed；READY 表示转写、知识增强和语义索引全部完成，FAILED 表示处理链路终止，其他阶段计入 processing。按本人资料记录统计，同一链接的幂等重放/重复提交不增加计数，日期使用首次投喂时保存的 business_date。默认业务时区为 Asia/Shanghai，UTC 午夜不作为日期切分点；后来修改账号时区不会重新移动已保存的业务日期。已转写但后续增强/索引失败的资料仍可通过内容详情读取。

统计使用现有 `(user_id,business_date,id)` 索引，单次聚合最多31组，不查询标题、原文、媒体或调用模型。附加 userId 不影响归属，未登录或已注销令牌401。计数为本次数据库读取时的状态，处理完成后刷新日历更新。逐日资料列表与每日总结在后续独立模块接入。

2026-10-06 日历4项真实 HTTP/MySQL 验证、状态3项与投喂5项共12项全部通过，构建与打包成功。包含闰年及空日期、9999年12月、所有阶段数量、重复链接不重复计数、月份与用户隔离、真实投喂逻辑在北京时间午夜两侧的日期归属、账号时区变动后保留历史日期、输入边界和注销后401。本轮未请求模型或云服务。

## 本人逐日资料列表 API

`GET /api/v1/fragments?date=2026-10-06&limit=10&before=...` 需要 Bearer 登录，响应 `Cache-Control: no-store`。date 必填，采用1000～9999年范围内的 YYYY-MM-DD；limit 默认10、范围1～20；before 首次省略，下一页原样使用 nextBefore，必须为正64位资料编号。非法日期/分页400 `INVALID_HISTORY_PAGE`。空日期与末页的 nextBefore 为 null。

响应包含 date、items、nextBefore，按 fragmentId 降序提供本人当天的全部处理阶段。卡片包含投喂业务日期、UTC 创建时间、来源域名、阶段和稳定错误码、标题/作者、分类、摘要预览，以及 contentPath 和受保护的 videoMediaPath/coverMediaPath。尚未解析的标题为“投喂记录”，无摘要时 summary=null、summaryOrigin=NONE；优先使用增强摘要（ENRICHED），尚未增强但已转写时显示听悟原摘要（TRANSCRIPTION），包括后续处理失败的资料。

标题最多500个 Unicode 字符、作者100、摘要预览400，分别提供 titleTruncated、authorTruncated、summaryTruncated；数据库原文与详情不被修改。分类使用已验证内容服务的8个主题校验，损坏资料使整页503 `CONTENT_INVALID`。不存在媒体检查点时对应路径为空，存在时仍需带登录调用媒体 API；不返回上游临时资源地址、OSS 对象键、签名、备注或云任务信息。

列表通过已有用户/业务日期/编号索引先取最多 limit+1 个编号，再复用内容服务；本页最多41次查询，全部在同一只读 REPEATABLE_READ 事务中，不读取转写全文或调用外部服务。跨页删除不会使游标失效，新增加的高编号通过刷新最新页可见。附加 userId 或其他用户的编号不能改变查询归属，未登录/注销后401。每日总结尚待独立模块接入。

2026-10-06 完整后端回归222项，207项通过、15项真实外部调用按开关跳过，构建与打包成功。其中逐日资料4项、日历4项、详情5项和状态3项共16项全部通过；覆盖23条跨页、边界删除与新记录、增强/原始摘要选择、Unicode 预览和原文保留、分类及媒体授权路径、失败/待处理显示、日期/用户隔离、参数边界、注销与异常分类拒绝。本轮媒体只用数据库夹具，未请求模型、OSS 或听悟，不代表 Android 播放闭环已验收。

## 定时工作线程隔离

现有解析、媒体保存、转写查询、知识增强和索引任务均在同步 fixedDelay 方法内执行。Spring Boot 默认调度器仅一个线程，长下载/上传/供应商查询会延迟其他任务；application.yml 现在配置 `spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:8}`，线程前缀 `fragpicker-scheduled-`。仅启用的任务参与调度，原有租约/所有权校验仍负责跨实例协调，同一 fixedDelay 任务在本实例保持不重叠；参见 [Spring Boot 3.5 调度配置](https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html)。

SCHEDULER_POOL_SIZE 必须为正整数，默认8为当前五类工作任务及日总结预留调度能力；缩小池容量会降低隔离能力。池大小不是每类任务的并发份数，也不保证 CPU/数据库过载时仍严格准点执行。未启用 Java 虚拟线程；后续若启用，Spring 的调度器及池配置行为须重新验证。每日总结定时业务尚未接入。

2026-10-06 实际 Spring Boot 配置加载/调度3项及解析、媒体、转写配置各2项，共9项全部通过。覆盖默认8线程在7个阻塞任务期间执行另一任务、环境参数绑定、同 fixedDelay 任务不重叠，以及非正池大小拒绝。验证不访问模型或云服务。

## 分批日总结生成器

`DailyDigestGenerator.generate` 提供独立可验证的生成业务方法；尚未提供日总结 HTTP API、持久化任务或22:00定时触发。它接收服务端指定的用户、业务日期、按资料编号降序排列的 READY 资料迭代器、取消检查和检查点回调。输入须由数据库按所有权/日期/状态加载，不能从客户端或模型 JSON 构造；生成器核对内部元数据的用户、日期、正编号和严格降序，不替代上游数据库归属查询。

每8份资料生成一份批次总结，每累积8份批次再合并到更高层，最后合并剩余各层。不限制当日总条数，也不静默忽略后续资料；同时保留的批次树随总量按对数增长，每次请求仍最多8份输入。空日返回明确的空总结，sourceCount/modelCalls 均为0，无模型调用。非空结果的 sourceCount 由服务端累加所有输入条数，modelCalls 为该生成树的实际请求次数；它们不会从模型响应读取，也不表示每条原始细节都在最终摘要中完整复述。

输入包含每份资料完整增强摘要（最多2000个 Unicode 字符）及全部最多8条/每条300字符的要点、主题分类；标题/作者预览分别200/100字符，关键词最多10个/每个32字符并明示截取。请求 JSON 最多98304个 UTF-16 单元。合并输入使用已校验批次摘要、要点及来源；引用候选仅限这些要点已验证的原始资料编号。正文作为引用数据，未注册工具，不提供云资源地址、凭据或用户编号。

响应严格为 summary、points、categories、keywords 四个字段。摘要最多1000个 Unicode 字符，要点1～8条/每条200字符，每条 sourceIds 必须为1～3个唯一的本次候选编号；分类1～8个唯一主题且须来自输入分类，关键词1～10个/每个32字符且唯一。拒绝未知字段、重复字段、尾随 JSON、错误类型、超长字段、不完整响应和伪造引用。最终来源编号及分类沿各层传播，不把模型生成的网址或卡片当成可信来源；文字本身仍是模型归纳，引用存在性不代表事实正确性已由人工核验。

本模块对日总结请求独立设置 `DIGEST_MAX_OUTPUT_TOKENS`（默认8192，1～32768）与 `DIGEST_JSON_OUTPUT`（默认true）。通过 LangChain4j 请求参数启用 JSON 输出；不支持 JSON 模式的兼容供应商可显式关闭，但本地结构与引用校验仍执行，且不自动降级重试。供应商以 LENGTH 结束时返回 `DIGEST_OUTPUT_LIMIT`，不会把可解析的残缺回答作为完成结果；其他非正常结束同样拒绝。JSON 模式不能代替结束原因和业务字段校验，参见 [DeepSeek 输出格式与完成原因](https://api-docs.deepseek.com/api/create-chat-completion/)。

默认每实例同时一项生成，忙时 `DIGEST_BUSY`，不排队；模型失败、取消、检查点失败、无模型分别为 `DIGEST_AI_UNAVAILABLE`、`DIGEST_CANCELLED`、`DIGEST_CHECKPOINT_FAILED`、`DIGEST_DISABLED`。不自动重试付费调用，不保存供应商错误正文；结构拒绝日志只含预设校验阶段。取消在读取和每次模型请求前后检查，不能强制中断同步 SDK 传输，其时间受已有 `AI_CHAT_TIMEOUT` 限制。每个成功模型节点交给检查点回调，回调失败即停止后续请求；节点持久化、依赖与恢复协议由后续任务模块实现，当前回调本身不代表已具备崩溃恢复能力。

2026-10-06 生成器10项契约、原知识增强3项、内容详情5项及1项真实 DeepSeek/MySQL 分批归纳共19项全部通过，构建与打包成功。129条合成输入经20次有界请求全部进入生成树；真实验证使用11份本人数据库摘要，经两次分批与一次合并检查原始来源归属。该真实样本为测试夹具，不是新的视频转写；尚未验收每日定时、次日补齐或 Android 日回顾。

完整无密钥后端回归236项，220项通过、16项可选真实外部调用按开关跳过，构建与打包成功。日总结真实测试开关位于类级别，禁用时不创建需要聊天密钥的 Spring 上下文；启用 `DIGEST_GENERATOR_TEST_ENABLED=true` 并加载本机聊天环境变量后，可用 `Server/scripts/Test-MySql.ps1` 重跑付费验证。
