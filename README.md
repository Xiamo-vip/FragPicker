# FragmentsPicker（FragPicker）

把碎片化的短视频信息，整理成可回顾、可检索的个人知识库。

## 项目简介

- **视频投喂**：粘贴或分享视频链接，自动解析、保存视频与封面、转写并生成简明标题、摘要、要点和关键词。
- **AI 对话**：通过自然语言查找历史内容，返回相关视频卡片，支持 Markdown 展示。
- **每日回顾**：首页查看今日投喂，日历回顾历史；北京时间每天 22:00 生成总结，次日补齐迟完成内容。
- **Android 界面**：Material 风格、液态玻璃导航、浅色 / 暗色主题与流畅动画。

| 目录 | 技术栈 |
| --- | --- |
| `Server` | Java 21、Spring Boot、MyBatis-Plus、LangChain4j、MySQL |
| `Android` | Kotlin、Jetpack Compose、Material 3 |

聊天支持 OpenAI 兼容供应商，默认示例使用 DeepSeek；语义检索使用 LangChain4j 本地中文 embedding，无需云端向量模型密钥。

## 环境准备

- **后端**：JDK 21、MySQL 8、PowerShell 7（以下命令以 Windows 为例）。
- **Android**：Android Studio，安装 SDK 36、Build Tools 35.0.0、platform-tools；设备最低 Android 8.0（API 26）。
- **外部服务**：自行部署 parse-video，准备私有 OSS Bucket、通义听悟应用及聊天供应商 API Key。
- 仓库自带 Maven / Gradle Wrapper，首次构建需要联网下载依赖。

```powershell
git clone https://github.com/Xiamo-vip/FragPicker.git
cd FragPicker
```

后续命令均从仓库根目录执行。

## 后端配置与运行

### 1. 初始化 MySQL

启动本机 MySQL，用管理员账号创建数据库：

```powershell
mysql -u root -p --execute="source Server/scripts/init-local-mysql.sql"
mysql -u root -p
```

在 MySQL 终端创建应用账号，替换示例密码：

```sql
CREATE USER 'fragpicker'@'localhost' IDENTIFIED BY '<your-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON fragpicker.* TO 'fragpicker'@'localhost';
```

已有账号可沿用自己的授权配置。后端启动时由 Flyway 自动建表和迁移，运行账号不使用 root。

### 2. 配置环境变量

```powershell
Copy-Item Server/.env.example Server/.env
```

编辑 `Server/.env`，填写以下核心项，并将解析服务与 Bucket 改成自己的配置。留空的凭据项需补齐；保留模板里的服务开关为 `true`。

```dotenv
SERVER_PORT=18080
DB_URL=jdbc:mysql://localhost:3306/fragpicker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=fragpicker
DB_PASSWORD=
JWT_SIGNING_KEY=

PARSEVIDEO_BASE_URL=http://127.0.0.1:8001/
AI_CHAT_BASE_URL=https://api.deepseek.com
AI_CHAT_MODEL=deepseek-flash
AI_CHAT_API_KEY=

OSS_ENDPOINT=oss-cn-shenzhen.aliyuncs.com
OSS_BUCKET=your-private-bucket
ALIBABA_CLOUD_ACCESS_KEY_ID=
ALIBABA_CLOUD_ACCESS_KEY_SECRET=
TINGWU_APP_KEY=
```

`JWT_SIGNING_KEY` 使用至少 32 个随机字节的 Base64 值。在自己的 PowerShell 7 终端生成一次，填入配置并保持稳定：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

配置说明：

- `.env` 已被 Git 忽略，真实密钥只保存在本机或秘密管理系统中。
- 启动脚本自动加载 `.env`，已有非空环境变量优先；IDE 或直接运行 JAR 时需要自行注入环境变量。
- `.env` 使用字面值，不支持变量替换或行内注释；修改配置后重启后端。
- OSS Endpoint 须与 Bucket 地域一致；STS 身份另配 `ALIBABA_CLOUD_SECURITY_TOKEN`。
- 解析接口须返回 `{"code":200,"data":{"video_url":"https://..."}}`，核对自己的服务版本与返回格式。
- 本地模型缓存目录须可写，可用 `INDEX_RUNTIME_DIRECTORY` 指定；Linux 部署还需兼容原生库（glibc ≥ 2.34）。

### 3. 构建并启动

先将 `JAVA_HOME` 指向本机 JDK 21：

```powershell
$env:JAVA_HOME = 'C:/Java/jdk-21' # 替换为实际路径
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp verify
.\Server\scripts\Start-Server.ps1 -Check
.\Server\scripts\Start-Server.ps1
```

启动器使用 `database` profile，默认读取 `Server/target/fragpicker-server-0.1.0-SNAPSHOT.jar`。在另一个终端检查：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
```

响应包含 `status: UP` 表示服务就绪；`-Check` 仅校验配置，不验证数据库或云服务连接。视频上传、转写和 AI 功能仍需真实服务联调。

IDEA 导入后端时关联 `Server/pom.xml`，或选择 `Server/build.gradle` + Gradle Wrapper / JDK 21；同一后端二选一导入。项目目录是 `Server`，不是 Gradle 的 `bin` 目录。

## Android 配置与运行

Android Studio 打开 `Android` 目录，配置 JDK 21 与 SDK。命令行也可设置 `ANDROID_HOME`，或在本机 `Android/local.properties` 中填写 `sdk.dir`。

```powershell
$env:ANDROID_HOME = 'C:/Android/Sdk' # 替换为实际路径
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=http://10.0.2.2:18080' :app:assembleDebug :app:lintDebug
adb devices
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

安装后打开 APP，注册账号并登录。`API_BASE_URL` 在构建时写入 APK，修改地址后重新构建安装。

| 使用场景 | 后端地址配置 |
| --- | --- |
| Android 模拟器 | `http://10.0.2.2:18080`，访问开发机后端 |
| USB 真机调试 | 先执行 `adb reverse tcp:18080 tcp:18080`，构建地址设为 `http://127.0.0.1:18080` |
| 远程 / 发布环境 | 使用自己的有效 HTTPS 地址 |

Debug 明文例外仅包含模拟器主机地址与回环地址，普通局域网 HTTP 不在例外中。Release 须显式配置 HTTPS 地址，并使用自己的发布签名：

```powershell
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=https://your-backend.example.com' :app:assembleRelease
```

## 引用项目与文档

| 项目 | 用途 |
| --- | --- |
| [Spring Boot](https://github.com/spring-projects/spring-boot) / [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) | 后端服务与数据访问 |
| [LangChain4j](https://github.com/langchain4j/langchain4j) | AI 对话、工具调用与本地 embedding |
| [parse-video-py](https://github.com/baige778/parse-video-py) | 多平台视频链接解析服务 |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) / [LiquidBottomTabs](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt) | 液态玻璃导航与交互组件 |
| [Shapes](https://github.com/Kyant0/Shapes) | 胶囊轮廓；移植来源与许可证见 [第三方声明](Android/THIRD_PARTY_NOTICES.md) |
| [Markwon](https://github.com/noties/Markwon) | AI Markdown 渲染 |
| [阿里云 OSS SDK](https://github.com/aliyun/aliyun-oss-java-sdk) / [听悟 SDK](https://github.com/aliyun/alibabacloud-java-sdk/tree/master/tingwu-20230930) | 媒体存储与转写 |

更多说明：[后端接口与配置](Server/README.md) · [Android 开发与测试](Android/README.md) · [项目规划](docs/PROJECT_PLAN.md) · [听悟接入文档](https://help.aliyun.com/zh/tingwu/offline-transcribe-of-audio-and-video-files)。依赖版本以 `Server/pom.xml` 和 Android Gradle 文件为准。
