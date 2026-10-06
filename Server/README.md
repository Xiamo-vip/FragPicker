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

为了让详情首屏响应有界，标题最多500个 Unicode 字符、作者100、原始摘要预览4000、增强摘要2000、增强要点最多8条/每条300、关键词最多100条/每条100。分别返回 `titleTruncated`、`authorTruncated`、`originalSummaryTruncated`、`summaryTruncated`、`pointsTruncated`、`keywordsTruncated`；数据库原始值保持完整。正常增强结果已满足这些展示边界。完整转写与带时间戳重点将在分页接口读取，不在详情首屏一次性加载。客户端应显示预览提示，而不能将标记为截断的字段宣称为全文。

详情和知识读取在只读 REPEATABLE_READ 事务中完成，保持此次响应的处理阶段与资料视图一致；没有模型或云资源调用。数据库 JSON 类型/分类异常返回503 `CONTENT_INVALID`，不回显原始内容或异常正文。API 不返回平台临时资源地址、OSS 对象键、云 TaskId、向量或租约信息。

2026-10-06 详情模块5项真实 HTTP/MySQL 验证全部通过，覆盖已完成与处理中/失败资料、预览边界、补充 Unicode 字符、原文保留、所有权及异常分类；同轮回归状态接口3项和真实语义搜索6项，共14项通过，构建与打包成功。媒体路径测试使用数据库夹具；私有 OSS 与上游转写真实验证见前文各模块记录。
