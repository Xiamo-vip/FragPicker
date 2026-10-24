# FragmentsPicker（FragPicker）

把保存的短视频，整理成可回顾、可检索的个人知识库。

- **视频投喂**：粘贴或分享视频链接，自动保存视频、封面、作者，并生成标题、摘要和要点。
- **AI 对话**：通过自然语言查找历史内容，回答附相关视频卡片，支持 Markdown 展示。
- **每日回顾**：首页查看今日记录，日历回顾历史；北京时间每天 22:00 总结，次日补齐迟完成内容。
- **Android 界面**：Material 风格、液态玻璃导航，支持浅色与暗色主题。

| 目录 | 技术栈 |
| --- | --- |
| `Server` | Java 21、Spring Boot、MyBatis-Plus、LangChain4j、MySQL |
| `Android` | Kotlin、Jetpack Compose、Material 3 |

聊天使用可配置的 OpenAI 兼容供应商，示例采用 DeepSeek；语义检索使用本地中文 embedding，无需云端向量模型密钥。

## 开始之前

以下步骤以 **Windows + PowerShell 7** 为例，按“解析服务 → 数据库 → 后端 → APP”的顺序操作。

| 用途 | 准备内容 |
| --- | --- |
| 运行后端 | JDK 21、MySQL 8 |
| 部署解析服务 | Docker Desktop，使用 Linux 容器；[安装说明](https://docs.docker.com/desktop/setup/install/windows-install/) |
| 构建 Android | Android Studio、SDK 36、Build Tools 35.0.0、platform-tools；设备最低 API 26 |
| 完整视频流程 | 私有 OSS Bucket、通义听悟应用、聊天供应商 API Key |

```powershell
git clone https://github.com/Xiamo-vip/FragPicker.git
cd FragPicker
```

后续命令均从仓库根目录执行。项目自带 Maven / Gradle Wrapper，首次构建需要联网下载依赖。

## 1 搭建 parse-video-py

[parse-video-py](https://github.com/baige778/parse-video-py) 是独立的视频解析服务。**APP 只连接 FragPicker 后端，由后端调用解析服务**，不将解析服务地址填进 APP。

### 启动服务

先安装并启动 Docker Desktop，再执行：

```powershell
docker version
docker pull ghcr.io/baige778/parse-video-py:latest
docker run -d --name fragpicker-parsevideo --restart unless-stopped `
  -p 127.0.0.1:8001:8000 `
  -v fragpicker-parsevideo-data:/data `
  ghcr.io/baige778/parse-video-py:latest
docker logs --tail 50 fragpicker-parsevideo
```

浏览器打开 `http://127.0.0.1:8001/`，确认解析页面可访问。

- 本例把**宿主机 8001** 映射到**容器 8000**，仅向本机开放。
- 命名卷保存 `/data` 中的运行数据，重建容器时继续使用同一卷。
- 已有同名容器时，先用 `docker ps -a` 检查状态；已停止的容器可用 `docker start fragpicker-parsevideo` 启动，不重复执行 `docker run`。
- 稳定部署可固定镜像版本或 digest；源码部署见[上游本地运行说明](https://github.com/baige778/parse-video-py#本地运行)。

### 验证解析接口

使用一条当前可正常访问的视频分享链接测试，无需先配置数据库或云服务：

```powershell
$shareUrl = Read-Host '粘贴视频分享链接'
$encodedUrl = [Uri]::EscapeDataString($shareUrl)
$result = Invoke-RestMethod "http://127.0.0.1:8001/video/share/url/parse?url=$encodedUrl" -TimeoutSec 120
$result | ConvertTo-Json -Depth 6
$isInteger = $result.code -is [int] -or $result.code -is [long]
if (-not $isInteger -or $result.code -ne 200 -or [string]::IsNullOrWhiteSpace($result.data.video_url)) {
    throw '解析未成功，请检查上方响应中的 code 和 msg。'
}
```

成功响应应包含**整数 `code: 200`** 和非空的 `data.video_url`，封面、作者等元数据可为空。以下仅为字段示意，资源地址以实际返回为准：

```json
{
  "code": 200,
  "msg": "解析成功",
  "data": {
    "video_url": "https://media.example.com/video.mp4",
    "cover_url": "https://media.example.com/cover.jpg",
    "title": "视频标题",
    "author": { "name": "作者名称" }
  }
}
```

页面能打开或 HTTP 返回 200，不代表视频解析成功；应检查上述业务字段。FragPicker 当前接收视频，不接收纯图集。接口封装依据[上游 Web 路由源码](https://github.com/baige778/parse-video-py/blob/main/src/parse_video_py/web.py)，不要将库函数的平铺返回示例当作 HTTP 接口格式。

## 2 初始化 MySQL

启动本机 MySQL，用管理员账号执行初始化，再进入 MySQL 终端：

```powershell
mysql -u root -p --execute="source Server/scripts/init-local-mysql.sql"
mysql -u root -p
```

创建应用账号，替换示例密码：

```sql
CREATE USER 'fragpicker'@'localhost' IDENTIFIED BY '<your-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON fragpicker.* TO 'fragpicker'@'localhost';
```

已有账号可沿用自己的配置。后端启动时由 Flyway 自动建表和迁移，运行账号不使用 root。

## 3 配置并启动 FragPicker 后端

### 填写环境变量

首次配置时复制模板；已有 `.env` 时直接编辑，保留本机配置：

```powershell
Copy-Item Server/.env.example Server/.env
```

编辑 `Server/.env`，补齐留空凭据，并按自己的服务修改地址与 Bucket。以下是核心配置，其他流程开关保留模板中的 `true`：

```dotenv
SERVER_PORT=18080
DB_URL=jdbc:mysql://localhost:3306/fragpicker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=fragpicker
DB_PASSWORD=
JWT_SIGNING_KEY=

PARSEVIDEO_ENABLED=true
INGESTION_WORKER_ENABLED=true
PARSEVIDEO_BASE_URL=http://127.0.0.1:8001/
PARSEVIDEO_USERNAME=
PARSEVIDEO_PASSWORD=

AI_CHAT_BASE_URL=https://api.deepseek.com
AI_CHAT_MODEL=deepseek-flash
AI_CHAT_API_KEY=

OSS_ENDPOINT=oss-cn-shenzhen.aliyuncs.com
OSS_BUCKET=your-private-bucket
ALIBABA_CLOUD_ACCESS_KEY_ID=
ALIBABA_CLOUD_ACCESS_KEY_SECRET=
TINGWU_APP_KEY=
```

`JWT_SIGNING_KEY` 使用至少 32 个随机字节的 Base64 值。生成一次，填入配置并保持稳定：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

### 解析服务如何对接

- 本机部署按上面的 `PARSEVIDEO_BASE_URL=http://127.0.0.1:8001/` 配置，**只填根地址**，不拼接 `/video/share/url/parse` 或 `?url=...`。
- 后端自动调用 `GET /video/share/url/parse?url=...`，处理分享链接编码；得到视频地址后，继续执行媒体保存、转写、摘要与索引。
- 解析服务在其他主机时，填写后端可访问的服务根地址，远程部署使用 HTTPS 与认证。本机的回环端口映射不直接供其他主机访问。
- 若后端也在容器内，`127.0.0.1` 指向后端容器自身；两容器加入同一自定义网络后，可按本例容器名配置 `http://fragpicker-parsevideo:8000/`。

若解析服务启用了 Basic Auth，两端使用不同的变量名，值需一致：

| 解析服务容器中的变量 | FragPicker 的 `Server/.env` 变量 |
| --- | --- |
| `PARSE_VIDEO_USERNAME` | `PARSEVIDEO_USERNAME` |
| `PARSE_VIDEO_PASSWORD` | `PARSEVIDEO_PASSWORD` |

本教程默认本机无认证。若需认证，将上游两项变量与自己的值写入本机 `.env.parsevideo`，在创建容器的 `docker run` 命令中加入 `--env-file .env.parsevideo`，再填写后端对应变量。已有容器需重新创建才能加载新环境，继续使用原数据卷。后端账号和密码须成对配置；认证连接仅接受 HTTPS 或本机回环 HTTP。

其他配置注意事项：

- `.env` 已被 Git 忽略；真实密钥保存在本机或秘密管理系统中。
- 启动脚本加载 `.env`，已有非空进程环境变量优先；IDE 或直接运行 JAR 时自行注入环境变量。修改后重启服务。
- `.env` 使用字面值，不支持变量替换或行内注释，不重复追加同名项；解析容器的 `PARSE_VIDEO_*` 变量不要写进后端 `.env`。
- OSS Endpoint 须与 Bucket 地域匹配；STS 身份额外配置 `ALIBABA_CLOUD_SECURITY_TOKEN`。
- 本地模型缓存目录须可写，可用 `INDEX_RUNTIME_DIRECTORY` 指定；Linux 部署还需兼容原生库（glibc ≥ 2.34）。

### 构建并启动

先准备好数据库、解析服务与完整流程所需的云配置，将 `JAVA_HOME` 指向本机 JDK 21：

```powershell
$env:JAVA_HOME = 'C:/Java/jdk-21' # 替换为实际路径
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp verify
.\Server\scripts\Start-Server.ps1 -Check
.\Server\scripts\Start-Server.ps1
```

启动器使用 `database` profile，默认读取 `Server/target/fragpicker-server-0.1.0-SNAPSHOT.jar`。另一终端检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
```

`status: UP` 表示服务就绪；`-Check` 只校验配置格式和依赖，不检查真实连接。解析成功也不等于上传、转写和索引均已完成，仍需按下一节联调。

IDEA 导入 `Server/pom.xml`，或选择 `Server/build.gradle` + Gradle Wrapper / JDK 21；同一后端二选一。项目目录是 `Server`，不是 Gradle 的 `bin` 目录。

## 4 运行 Android 并联调

Android Studio 打开 `Android` 目录，配置 JDK 21 和 SDK。也可通过本机 `Android/local.properties` 设置 `sdk.dir`，或在终端指定 SDK：

```powershell
$env:ANDROID_HOME = 'C:/Android/Sdk' # 替换为实际路径
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=http://10.0.2.2:18080' :app:assembleDebug :app:lintDebug
adb devices
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

| 场景 | APP 的 `API_BASE_URL` |
| --- | --- |
| Android 模拟器 | `http://10.0.2.2:18080`，访问开发机后端 |
| USB 真机 | 先执行 `adb reverse tcp:18080 tcp:18080`，构建地址设为 `http://127.0.0.1:18080` |
| 远程 / 发布 | 自己部署的有效 HTTPS 后端地址 |

地址在构建时写入 APK，修改后重新构建安装。Debug 明文例外仅包含模拟器主机与回环地址，普通局域网 HTTP 不在例外中；Release 须指定 HTTPS 地址并使用自己的发布签名。

安装后按以下顺序验证：

1. 注册并登录 APP，用第 1 节验证成功的链接投喂。
2. 查看任务反馈，等待整理完成；在详情页查看摘要及可用的封面、作者信息。
3. 播放视频或打开原始链接，在回顾页查看当天记录。
4. 在 AI 对话页询问该视频，检查返回的文字与来源卡片是否相关。

## 5 常见问题

| 现象 | 检查方向 |
| --- | --- |
| Docker 连接失败 / 容器退出 | 确认 Docker Desktop 已启动并使用 Linux 容器；查看 `docker logs fragpicker-parsevideo` |
| 8001 端口被占用 | 修改 `-p` 中的宿主机端口，并同步修改接口测试地址和 `PARSEVIDEO_BASE_URL`；容器端口仍是 8000 |
| 解析接口返回 401 | 检查 Basic Auth 是否启用，两端变量名和账号密码是否对应 |
| HTTP 200 但解析失败 | 查看 JSON 的 `code`、`msg`、`data.video_url`；确认分享链接有效且属于服务支持的视频平台 |
| 解析成功但任务未完成 | 分别检查 OSS、听悟、聊天配置及工作器状态；媒体地址可能过期或不可下载，资源须能从后端通过公网访问 |
| 后端找不到解析服务 | 区分回环地址、容器网络和远程地址；APP 地址填后端 18080，而不是解析服务 8001 |

## 引用项目与文档

| 项目 | 用途 |
| --- | --- |
| [Spring Boot](https://github.com/spring-projects/spring-boot) / [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) | 后端服务与数据访问 |
| [LangChain4j](https://github.com/langchain4j/langchain4j) | AI 对话、工具调用与本地 embedding |
| [parse-video-py](https://github.com/baige778/parse-video-py) | 多平台视频链接解析服务 |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) / [LiquidBottomTabs](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt) | 液态玻璃导航与交互组件 |
| [Shapes](https://github.com/Kyant0/Shapes) | 胶囊轮廓；来源与许可证见 [第三方声明](Android/THIRD_PARTY_NOTICES.md) |
| [Markwon](https://github.com/noties/Markwon) | AI Markdown 渲染 |
| [阿里云 OSS SDK](https://github.com/aliyun/aliyun-oss-java-sdk) / [听悟 SDK](https://github.com/aliyun/alibabacloud-java-sdk/tree/master/tingwu-20230930) | 媒体存储与转写 |

更多说明：[后端接口与配置](Server/README.md) · [Android 开发与测试](Android/README.md) · [作品介绍](docs/work-introduction/FragmentsPicker_作品介绍.md) · [项目规划](docs/PROJECT_PLAN.md) · [听悟接入](https://help.aliyun.com/zh/tingwu/offline-transcribe-of-audio-and-video-files)。依赖版本以 `Server/pom.xml` 和 Android Gradle 文件为准。
