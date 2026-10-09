# FragmentsPicker / FragPicker

## 复现性声明与重现指南

将主动保存的短视频整理成可回顾、可检索的个人知识记录。项目面向个人自用：Android 投喂分享链接，后端解析、保存媒体、转写并生成知识摘要；用户通过首页、历史回顾和 AI 对话检索自己的内容。

**项目仓库：<https://github.com/Xiamo-vip/FragPicker>**

本文以 **2026-10-09 的代码基线**为准。其他文档保留了逐模块交付记录；其中的历史版本、阶段性待办和测试数量不应直接当作当前版本的结论。

### 导航

1. [复现范围与基线](#1-复现范围与基线)
2. [环境与目录](#2-环境与目录)
3. [依赖清单与 GitHub 来源](#3-依赖清单与-github-来源)
4. [MySQL 初始化](#4-mysql-初始化)
5. [环境变量模板与加载方式](#5-环境变量模板与加载方式)
6. [外部服务配置](#6-外部服务配置)
7. [后端构建与启动](#7-后端构建与启动)
8. [Android 构建与安装](#8-android-构建与安装)
9. [测试与端到端验收](#9-测试与端到端验收)
10. [完整配置项索引](#10-完整配置项索引)
11. [排错与复现实验记录](#11-排错与复现实验记录)

## 1. 复现范围与基线

### 1.1 固定代码版本

| 项目 | 本文基线 |
| --- | --- |
| 可重现代码提交（含启动器配置修复） | `7d553b5f62288dd15c2efcd53ae3946d1cbb3b27` |
| Android | `versionName=0.1.4`，`versionCode=5`，包名 `com.fragpicker.android` |
| 后端 | `com.fragpicker:fragpicker-server:0.1.0-SNAPSHOT` |
| 数据库迁移 | Flyway `V1` 至 `V16`，以迁移目录为准 |
| 业务日期 | `Asia/Shanghai`；每日北京时间 22:00 总结，次日补齐迟完成内容，不推送 |
| 聊天 | OpenAI 兼容接口，示例采用 DeepSeek；不是百炼聊天 |
| 向量模型 | LangChain4j 本地 `bge-small-zh-v1.5-q@langchain4j-1.21.0-beta31`，512 维 |

```powershell
git clone https://github.com/Xiamo-vip/FragPicker.git
cd FragPicker
git rev-parse HEAD
# 精确重现上述功能代码时执行；该提交中的 README 早于本文修订。
# 请先保存本指南，并保持工作区没有需要保留的未提交变更。
git checkout 7d553b5f62288dd15c2efcd53ae3946d1cbb3b27
```

使用后续版本时，记录实际提交并以该提交中的构建文件、配置和迁移为准，不混用不同版本的 APK、后端 JAR 与数据库结构。

### 1.2 声明与限制

- **可重现目标**：从固定源码构建 JAR/APK，初始化数据库，运行自动化测试，并在自行配置外部服务后检验业务流程。测试夹具与真实服务验收分别记录。
- **不是逐字节构建保证**：当前没有承诺跨机器 JAR/APK 的 SHA-256 完全相同。构建时间、平台、工具链、依赖解析和 Android 签名均可能影响输出；仓库也没有覆盖全部传递依赖的锁定快照。Wrapper 固定下载版本并校验分发包，依赖版本由 POM、Gradle 和 BOM 管理。
- **不是生成文本一致性保证**：云端转写、LLM 版本与采样、视频源变化、签名 URL、异步完成时间会影响结果。验收比较状态、字段、引用来源、检索相关性和播放能力，不要求摘要逐字一致。薪火粒子本身随机，截图不要求逐像素一致。
- **本地 embedding 不等于首次构建离线**：量化模型随 Maven 依赖提供，推理在后端进程中执行，不需要 embedding API Key；首次构建仍需下载依赖和原生运行库。512 维向量存入 MySQL，本版本不要求额外部署向量数据库。
- **已记录的验证环境**：Windows x64、JDK/JBR `21.0.8`、MySQL `8.0.30`、Android API 36 x86_64 模拟器。Linux 启动示例是部署说明，不是本文对 Linux 全流程测试通过的声明。
- **验证边界**：历史交付记录包含真实隔离 MySQL、Android HTTP 夹具和 UI 回归；真实 OSS → 听悟 → Android 播放全链路仍需配置云环境后单独验收。编译成功、配置校验通过、测试被跳过或模拟响应通过，均不等同于真实云联调通过。
- **外部服务未完全固定**：本仓库未记录当前部署的 parse-video 服务提交/镜像摘要，也不固定云供应商的模型权重版本。完整实验须自行记录这些版本及样本信息，见第 11 节。

## 2. 环境与目录

### 2.1 环境要求

| 环境 | 要求 / 说明 |
| --- | --- |
| Git | 支持克隆 GitHub 仓库；记录 `git --version` |
| JDK | 后端使用 **21**；建议前后端统一用 JDK 21，Android 字节码目标仍为 17 |
| Maven | 使用仓库 Maven Wrapper，下载 **3.9.11**；不要求全局安装 Maven |
| Gradle | 两个项目均使用 Wrapper **8.13**；不要求全局安装 Gradle |
| Windows 脚本 | 建议 PowerShell 7；下文 Windows 命令均从仓库根目录执行 |
| MySQL | MySQL 8 系列；已验证 **8.0.30**，8.4 属于项目支持目标而非本文新增验证结果 |
| Android SDK | Platform **36**、Build Tools **35.0.0**（本项目 AGP 的默认版本）、platform-tools；设备测试另需 emulator / 系统镜像 |
| Android 系统 | 最低 **API 26 / Android 8.0**；编译 / 目标 API 36；验收建议 API 36 模拟器 |
| Linux 原生库 | 本地分词器曾出现 `GLIBC_2.34` 缺失；使用 glibc ≥ 2.34 的兼容发行版并核对 CPU 架构；不直接假定 Alpine/musl 兼容 |
| 文件权限 | 后端账号对 embedding 缓存及媒体暂存目录有读写权限；多实例分别指定运行目录 |
| 网络 | 首次构建需访问 Maven Central、Google Maven、Gradle 分发及插件仓库；完整业务还需访问解析服务、媒体源、OSS、听悟和聊天服务 |
| 资源预算 | 工程建议：后端预留约 4 GiB 内存，含模拟器的开发机预留 8 GiB 以上；这是起步建议，不是经过压测的容量保证 |

Android Studio / IDEA 版本不硬编码，选择支持本项目 AGP 与 Gradle 的版本。Docker 仅是解析服务的一种部署方式，后端与 Android 本身不要求 Docker、Node.js 或 Python 环境。

```text
FragPicker/
├── README.md                         # 本指南
├── Server/
│   ├── pom.xml                       # 后端直接依赖的维护入口
│   ├── settings.gradle / build.gradle # 独立后端 Gradle 项目
│   ├── mvnw / mvnw.cmd / gradlew*     # 构建 Wrapper
│   ├── .env.example                  # 核心变量示例，无真实凭据
│   ├── scripts/                      # 启动、数据库初始化、隔离测试
│   └── src/main/resources/
│       ├── application*.yml          # 配置引用环境变量
│       └── db/migration/             # Flyway V1–V16
├── Android/
│   ├── settings.gradle.kts / build.gradle.kts
│   ├── app/                          # Kotlin + Compose 应用与设备测试
│   └── THIRD_PARTY_NOTICES.md         # LiquidGlass 等移植代码声明
└── docs/                             # 规划书、快速启动、历史验收记录
```

**仓库根目录不是 Gradle 项目**。`Server` 与 `Android` 是两个独立构建；`.tools`、构建产物、IDE 配置、运行缓存都是本机文件，不是重现的前置下载包。

## 3. 依赖清单与 GitHub 来源

下表列实际引入的直接依赖和关键运行库，不将规划中的待选库当作已使用依赖。完整传递依赖用本节末尾命令导出。

### 3.1 后端

版本以 [Server/pom.xml](Server/pom.xml) 为维护入口；后端 Gradle 读取其中的直接依赖、版本和排除项。升级 Spring Boot 时还需同步后端 Gradle 插件版本，构建会检查一致性。

| 依赖 / Maven 坐标 | 版本 / 范围 | 用途及 GitHub 来源 |
| --- | --- | --- |
| Spring Boot parent；`spring-boot-starter-web`、`spring-boot-starter-actuator`、`spring-boot-starter-validation` | `3.5.16` / 主程序 | Web、健康检查、校验；[spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) |
| `spring-boot-starter-oauth2-resource-server` | Boot 管理 / 主程序 | JWT 资源服务；[spring-projects/spring-boot](https://github.com/spring-projects/spring-boot)、[spring-projects/spring-security](https://github.com/spring-projects/spring-security) |
| `org.springframework.security:spring-security-crypto` | Boot 管理；当前解析 `6.5.11` / 主程序 | BCrypt；[spring-projects/spring-security](https://github.com/spring-projects/spring-security) |
| `com.baomidou:mybatis-plus-spring-boot3-starter` | `3.5.17` / 主程序 | MySQL 数据访问；[baomidou/mybatis-plus](https://github.com/baomidou/mybatis-plus) |
| `com.mysql:mysql-connector-j` | Boot 管理；当前解析 `9.7.0` / runtime | JDBC 驱动，**不是 MySQL 服务端版本**；[mysql/mysql-connector-j](https://github.com/mysql/mysql-connector-j) |
| `org.flywaydb:flyway-core`、`org.flywaydb:flyway-mysql` | Boot 管理；当前解析 `11.7.2` / 主程序 | 数据库迁移；[flyway/flyway](https://github.com/flyway/flyway) |
| `dev.langchain4j:langchain4j-open-ai` | `1.21.0` / 主程序 | OpenAI 兼容模型与 Tool 调用；[langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) |
| `dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15-q` | `1.21.0-beta31` / 主程序 | 本地中文量化 embedding；[模型模块源码](https://github.com/langchain4j/langchain4j/tree/main/embeddings/langchain4j-embeddings-bge-small-zh-v15-q) |
| `com.aliyun:tingwu20230930` | `2.0.26` / 主程序 | 听悟离线转写 SDK；[aliyun/alibabacloud-java-sdk](https://github.com/aliyun/alibabacloud-java-sdk/tree/master/tingwu-20230930) |
| `com.aliyun.oss:aliyun-sdk-oss` | `3.18.4` / 主程序 | 私有视频、封面存储；[aliyun/aliyun-oss-java-sdk](https://github.com/aliyun/aliyun-oss-java-sdk) |
| `org.apache.httpcomponents:httpclient` | `4.5.14` / 主程序 | 下载及响应读取；[apache/httpcomponents-client](https://github.com/apache/httpcomponents-client) |
| `javax.xml.bind:jaxb-api` | `2.3.1` / 主程序 | 旧 SDK JAXB 兼容接口；[jakartaee/jaxb-api](https://github.com/jakartaee/jaxb-api)，这里实际坐标仍为 `javax.xml.bind` |
| `spring-boot-starter-test` | Boot `3.5.16` 管理 / test | JUnit 等测试；[spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) |
| `org.junit.platform:junit-platform-launcher` | Boot BOM 管理 / 仅 Gradle testRuntimeOnly | Gradle JUnit 启动器补充；[junit-team/junit-framework](https://github.com/junit-team/junit-framework) |
| `com.microsoft.onnxruntime:onnxruntime` | 当前传递解析 `1.20.0` | 本地推理原生库；[microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| `ai.djl:api`、`ai.djl.huggingface:tokenizers` | 当前传递解析 `0.36.0` | 本地分词；[deepjavalibrary/djl](https://github.com/deepjavalibrary/djl) |

HTTP Client 与 OSS SDK 排除了 `commons-logging`，具体排除项见 POM。上述传递版本是当前解析结果，升级后应重新导出依赖，而非手动把传递依赖全部提升为直接依赖。

### 3.2 Android

版本以 [Android/build.gradle.kts](Android/build.gradle.kts) 和 [Android/app/build.gradle.kts](Android/app/build.gradle.kts) 为准。

| 依赖 / 配置 | 固定版本 | 用途及 GitHub 来源 |
| --- | --- | --- |
| Android Gradle Plugin | `8.13.2` | Android 构建；[官方源码](https://android.googlesource.com/platform/tools/base/)（官方托管于 AOSP，不杜撰 GitHub 地址） |
| Kotlin Android / Compose 编译插件 | `2.2.21` | Kotlin、Compose 编译；[JetBrains/kotlin](https://github.com/JetBrains/kotlin) |
| `androidx.compose:compose-bom` | `2025.10.01` | 管理下列 Compose 库版本；[androidx/androidx](https://github.com/androidx/androidx) |
| Compose `ui`、`ui-tooling-preview`、Material 3 `material3`、`material-icons-extended` | 由上述 BOM 管理 | 界面、预览、图标；[androidx/androidx](https://github.com/androidx/androidx) |
| `androidx.activity:activity-compose`、`activity-ktx` | `1.11.0` | Activity 与 Compose 接入；[androidx/androidx](https://github.com/androidx/androidx) |
| Lifecycle `lifecycle-runtime-compose`、`lifecycle-viewmodel-compose` | `2.9.4` | 生命周期与 ViewModel；[androidx/androidx](https://github.com/androidx/androidx) |
| `androidx.datastore:datastore-preferences` | `1.1.7` | 本机偏好存储；[androidx/androidx](https://github.com/androidx/androidx) |
| `io.github.kyant0:backdrop` | `1.0.0` | 液态玻璃绘制；[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) |
| `io.noties.markwon:core` | `4.6.2` | AI Markdown 渲染；[noties/Markwon](https://github.com/noties/Markwon) |
| Compose `ui-tooling`、`ui-test-manifest` | BOM 管理 / debug | 调试工具与测试承载 Activity；[androidx/androidx](https://github.com/androidx/androidx) |
| Compose `ui-test-junit4` + Compose BOM | BOM 管理 / androidTest | Compose 设备测试；[androidx/androidx](https://github.com/androidx/androidx) |
| `androidx.test:runner`、`androidx.test.ext:junit` | `1.6.2`、`1.2.1` / androidTest | 测试运行器；[android/android-test](https://github.com/android/android-test) |

Android 当前直接使用平台网络、JSON 与播放器能力；Retrofit、Room、Coil、Media3 不属于本基线的直接依赖。

### 3.3 引用 / 移植项目

- **视频解析服务**：[baige778/parse-video-py](https://github.com/baige778/parse-video-py)。这是单独运行的外部服务，不是本仓库内嵌 Python 模块；部署者记录其实际 commit / 镜像 digest。多平台可用性取决于该服务与平台现状。
- **LiquidBottomTabs 及交互组件**：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，本项目移植基准为 [`65ab177e90e5c1d8c62e70cf7755841982da65f6`](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6)；[上游 LiquidBottomTabs 入口](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt)。应用存在 Android 适配及裁剪等修改，不将移植后的代码声称为未经修改的上游实现。
- **Capsule 与轮廓辅助源码**：[Kyant0/Shapes](https://github.com/Kyant0/Shapes)，移植自官方 `shapes:1.2.1` 源码，不是另一项 Gradle 直接依赖。
- 移植来源、修改范围见 [Android/THIRD_PARTY_NOTICES.md](Android/THIRD_PARTY_NOTICES.md)；Apache-2.0 文本保存在 [APK 许可证资源](Android/app/src/main/assets/licenses/AndroidLiquidGlass.txt) 中。使用、再分发时保留第三方声明，第三方许可证不自动等同于整个 FragPicker 项目的许可证。
- 工具链源码：[apache/maven](https://github.com/apache/maven)、[gradle/gradle](https://github.com/gradle/gradle)。

### 3.4 导出完整解析依赖

从仓库根目录执行；`.tools` 是这里自行创建的输出目录，不需要复制开发者的缓存。

```powershell
New-Item -ItemType Directory -Path .tools/repro -Force | Out-Null
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp dependency:tree '-DoutputFile=../.tools/repro/server-dependency-tree.txt'
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp dependency:list '-DoutputFile=../.tools/repro/server-dependencies.txt'
.\Android\gradlew.bat -p Android :app:dependencies --configuration debugRuntimeClasspath > .tools/repro/android-debug-dependencies.txt
# 测试依赖和 Release 依赖分别导出，不以 Debug 报告代替全部变体。
.\Android\gradlew.bat -p Android :app:dependencies --configuration debugAndroidTestRuntimeClasspath > .tools/repro/android-test-dependencies.txt
.\Android\gradlew.bat -p Android :app:dependencies --configuration releaseRuntimeClasspath > .tools/repro/android-release-dependencies.txt
```

Wrapper 分发包 SHA-256：Maven `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb`；Gradle `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。配置见各 Wrapper properties 文件，不手动删除校验来绕过下载异常。

Maven 的用户级 `~/.m2/settings.xml` / `MAVEN_ARGS`、Gradle 用户配置或初始化脚本可能改变镜像、代理和解析来源。重现时记录非敏感的仓库来源及自定义配置，并检查报告是否出现 `FAILED` / 未解析项；Gradle 的依赖报告任务退出成功不自动表示所有依赖已解析。不要将本机私有仓库或缓存配置当作项目的公共依赖来源。

## 4. MySQL 初始化

安装并启动本机 MySQL，确认 `mysql` / `mysqld` 可执行文件的位置。以下命令用管理员身份创建数据库，不覆盖已有内容：

```powershell
mysql -u root -p --execute="source Server/scripts/init-local-mysql.sql"
mysql -u root -p
```

随后在 MySQL 交互终端执行（替换示例密码，不把真实密码提交到仓库）：

```sql
CREATE USER 'fragpicker'@'localhost' IDENTIFIED BY '<replace-with-local-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON fragpicker.* TO 'fragpicker'@'localhost';
SHOW GRANTS FOR 'fragpicker'@'localhost';
```

账户已存在时按自己的管理流程更新凭据，而非重复创建。初始化文件使用 `utf8mb4` / `utf8mb4_0900_ai_ci`；表结构由 `database` profile 启动时的 Flyway 自动创建并校验。应用使用 `fragpicker` 账号，不使用 root。远程 MySQL 须单独配置对应来源授权及 TLS，不能直接套用 `'localhost'` 授权。

- 空数据库：正常启动会执行 `V1`–`V16`。
- 现有数据库：先备份，再让 Flyway 顺序升级；保留 `flyway_schema_history`，不重写已执行的迁移。
- `clean-disabled=true`、`validate-on-migrate=true`；校验错误应核对实际 schema 与迁移历史，不删除历史表来掩盖不一致。
- 生产库与测试库分离；**不将 `DB_TEST_URL` 指向个人真实内容库**。测试可创建 / 删除测试 schema，权限要求不同于运行账号。

## 5. 环境变量模板与加载方式

### 5.1 完整流程核心模板

```powershell
Copy-Item Server/.env.example Server/.env
```

编辑本机 `Server/.env`，按下面模板替换服务地址和凭据。仓库示例中的已有测试部署地址不代表公共服务承诺，应改成自己的解析服务。以下留空项必须填写；`your-private-bucket` 是占位名称。

```dotenv
# 本机业务服务；application.yml 的裸默认端口是 8080，本指南统一使用 18080。
SERVER_PORT=18080
DB_URL=jdbc:mysql://localhost:3306/fragpicker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=fragpicker
DB_PASSWORD=
JWT_SIGNING_KEY=

# 所有 12 个生产服务流程开关默认开启；保留 true 表示完整业务。
AI_CHAT_ENABLED=true
PARSEVIDEO_ENABLED=true
OSS_ENABLED=true
TINGWU_ENABLED=true
INGESTION_WORKER_ENABLED=true
MEDIA_WORKER_ENABLED=true
TRANSCRIPTION_WORKER_ENABLED=true
KNOWLEDGE_ENRICHMENT_ENABLED=true
KNOWLEDGE_INDEX_ENABLED=true
DIGEST_WORKER_ENABLED=true
DIGEST_SCHEDULE_ENABLED=true
MEDIA_CLEANUP_ENABLED=true

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
ALIBABA_CLOUD_SECURITY_TOKEN=
TINGWU_APP_KEY=
TINGWU_SOURCE_LANGUAGE=auto

# 可选：固定到部署账号可写的绝对目录；相对路径以服务工作目录为基准。
INDEX_RUNTIME_DIRECTORY=.fragpicker-runtime
```

JWT 密钥必须是至少 **32 个随机字节的 Base64**。在自己的终端生成一次，填入本机配置；同一部署后续启动保持稳定，不每次启动重新生成：

```powershell
$bytes = [byte[]]::new(32)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
[Convert]::ToBase64String($bytes)
```

### 5.2 配置如何生效

- `Server/scripts/Start-Server.ps1` 加载指定的 `.env`，默认 `Server/.env`；支持 `-EnvFile` 指向其他本机配置。
- **已有非空进程环境变量优先于 `.env`**。检查同名变量是否还保留旧地址 / 开关；修改后重启服务。启动器退出时恢复本次加载前的环境。
- `.env` 使用 `NAME=字面值`，UTF-8；整行 `#` 注释和空行有效，重复键会报错。可剥除一对匹配的引号，但不执行变量替换、命令、`export` 或行内注释。密码里的 `#` 等字符属于值。
- **Spring Boot / Maven / Gradle 不自动读取 `.env`**。直接运行 JAR、`bootRun`、`spring-boot:run` 或 IDE 时，应由运行配置 / 秘密管理系统注入环境变量；不要误以为复制模板就已经完成注入。
- Android 的 `API_BASE_URL` 是 **Gradle project property**，不是后端 `.env` 变量；构建时用 `-PAPI_BASE_URL=...` 或本机 Gradle 配置提供。
- `.env`、`.env.*`（保留 `.env.example`）、`application-local.yml`、`local.properties`、密钥库与 `.tools` 已在 `.gitignore` 中。忽略规则不代替密钥管理，已泄露或误提交的真实凭据应轮换。

### 5.3 不调用外部服务的本地模式

用于先跑账户、数据库、页面和队列接口，不代表完整视频转写 / AI 功能可用。**不修改默认开启行为**：复制本机配置为 `Server/.env.local`，仅在该配置中将上面的 12 个开关全部设为 `false`；保留数据库三项与 JWT 密钥。

```powershell
Copy-Item Server/.env Server/.env.local
# 手动将 .env.local 中 12 个 *_ENABLED 服务开关全部改为 false。
.\Server\scripts\Start-Server.ps1 -EnvFile Server/.env.local -Check
```

此模式投喂请求可入队，但没有工作器推进；不期待真实摘要、搜索索引或媒体播放完成。若要单独验证本地向量索引，可开启 `KNOWLEDGE_INDEX_ENABLED=true` 并使用测试夹具，同时满足第 6.4 节原生运行要求。

### 5.4 开关依赖关系

| 开启的流程 | 同时需要开启 |
| --- | --- |
| `INGESTION_WORKER_ENABLED` | `PARSEVIDEO_ENABLED` |
| `MEDIA_WORKER_ENABLED` | `PARSEVIDEO_ENABLED`、`OSS_ENABLED` |
| `TRANSCRIPTION_WORKER_ENABLED` | `OSS_ENABLED`、`TINGWU_ENABLED` |
| `KNOWLEDGE_ENRICHMENT_ENABLED`、`DIGEST_WORKER_ENABLED` | `AI_CHAT_ENABLED` |
| `DIGEST_SCHEDULE_ENABLED` | `DIGEST_WORKER_ENABLED` |
| `MEDIA_CLEANUP_ENABLED` | `OSS_ENABLED` |

`KNOWLEDGE_INDEX_ENABLED` 使用本地模型，不依赖聊天 API Key。`-Check` 只校验必填值、密钥格式、布尔值和流程依赖，**不验证数据库、供应商余额、API 可达性或 Bucket 权限**。

## 6. 外部服务配置

### 6.1 parse-video 服务与实际返回契约

按照 [parse-video-py 官方仓库](https://github.com/baige778/parse-video-py) 自行部署，或填写自己的现有服务。选择源码 commit 或不可变镜像 digest 并记录，不用浮动 `latest` 声称精确重现。Docker 的模板示例如下，先将摘要占位符替换为自己核验过的镜像摘要：

```sh
docker run -d --name fragpicker-parsevideo \
  -p 127.0.0.1:8001:8000 \
  -v fragpicker-parse-data:/data \
  ghcr.io/baige778/parse-video-py@sha256:YOUR_VERIFIED_DIGEST
```

此模板不是已验证的上游镜像版本声明；源代码部署的 Python / Playwright 等依赖按选定上游版本的说明安装。后端请求：

```text
GET {PARSEVIDEO_BASE_URL}/video/share/url/parse?url={UTF-8 URL 编码后的分享链接}
```

**当前 FragPicker 适配器要求 HTTP 2xx 且响应为以下 envelope**（示例资源域名仅说明格式）：

```json
{
  "code": 200,
  "data": {
    "video_url": "https://media.example.com/video.mp4",
    "cover_url": "https://media.example.com/cover.jpg",
    "title": "视频原始标题",
    "author": { "name": "作者名称" }
  }
}
```

上游文档中亦有平铺字段的展示示例；**不要仅凭项目同名推定返回格式兼容**。联调时检查实际服务返回：本适配器需要整数 `code=200`、对象 `data` 和其中的 `video_url`。若部署返回平铺字段，应配置兼容响应层或调整并测试适配器后再接入。图片分享不等同于视频，纯图内容会被判为不支持。

可选 Basic Auth 通过 `PARSEVIDEO_USERNAME` / `PARSEVIDEO_PASSWORD` 成对配置，远端服务使用 HTTPS；HTTP 仅用于回环本机。解析出的媒体资源必须是可下载的公开网络地址；内网、回环、Fake-IP 解析或不合规跳转会被媒体网络校验拦截。不要为联调关闭这类检查，应修正资源地址或代理解析方式。

### 6.2 阿里云 OSS 与通义听悟

1. 创建自己的 **私有 OSS Bucket**，填写名称和所在地域 Endpoint。当前项目约定示例为 `oss-cn-shenzhen.aliyuncs.com`，实际 Endpoint 必须与 Bucket 匹配；Bucket 名称不是凭据，也不意味着已经获得访问权。
2. 为部署账号配置相应的 Bucket 检查、媒体上传、读取和删除权限；清理涉及的版本权限按 Bucket 版本控制情况配置。开启 OSS 时启动会校验 Bucket 私有性，不将 Bucket 改成公共读来解决鉴权错误。
3. 开通听悟离线音视频转写应用，配置 `TINGWU_APP_KEY` 及 `ALIBABA_CLOUD_ACCESS_KEY_ID` / `ALIBABA_CLOUD_ACCESS_KEY_SECRET`；STS 模式额外填写 `ALIBABA_CLOUD_SECURITY_TOKEN` 并管理过期时间。
4. 当前听悟 SDK 的 Region / Endpoint 在代码中为 `cn-beijing` / `tingwu.cn-beijing.aliyuncs.com`，**不是 OSS 的深圳 Endpoint**；核对应用与服务开通条件。调用按 [听悟官方离线转写文档](https://help.aliyun.com/zh/tingwu/offline-transcribe-of-audio-and-video-files) 验收。
5. 听悟须能读取后端生成的视频签名 URL；提交转写的 URL 默认有效 4 小时，APP 播放签名默认 5 分钟，二者用途不同。过期后重新请求媒体接口，不永久保存签名链接。

视频和封面长期保存在私有 OSS，数据库保存检索元数据与任务结果。用户删除内容后的资源清理属于独立工作器；备份数据库时还需考虑对应对象资源。完整联调会产生 OSS、转写和模型费用，使用自己的测试样本、余额与配额。

### 6.3 DeepSeek / 其他 OpenAI 兼容聊天供应商

配置 `AI_CHAT_BASE_URL`、`AI_CHAT_API_KEY`、`AI_CHAT_MODEL`。现有示例是 `https://api.deepseek.com` / `deepseek-flash`，参见 [DeepSeek 官方 API 文档](https://api-docs.deepseek.com/quick_start)。项目只读取 `AI_CHAT_API_KEY`，不会自动把其他命名的供应商 Key 当作该变量。

替换供应商时填写其文档要求的 OpenAI 兼容 **根地址**，不要把 `/chat/completions` 当作根地址。所选模型还应支持本项目实际使用的 Tool 调用、流式响应和结构化 JSON 输出；仅普通问答成功不证明每日总结和检索工具也兼容。记录模型名和供应商版本，不在 APK 内嵌这些凭据。

### 6.4 本地语义索引

模型通过 [LangChain4j 本地 embedding 集成](https://docs.langchain4j.dev/integrations/embedding-models/in-process/) 加载，使用 ONNX Runtime 和 DJL Tokenizers；无独立云 embedding 配置。

- `INDEX_RUNTIME_DIRECTORY` 默认是服务工作目录下 `.fragpicker-runtime`，启动会准备 `tmp`、`djl` 及平台对应的 ONNX 原生库目录。建议部署时指定固定、可写的绝对路径，不以只读 JAR 目录当缓存。
- 可选进程变量 `DJL_CACHE_DIR` 会覆盖默认分词缓存；它是运行库变量，不属于 `Start-Server.ps1` 的 `.env` 白名单，需在启动进程环境中设置。`onnxruntime.native.path` 是高级 JVM 系统属性，不是应用环境变量；常规部署不必设置。
- 开启 `database` profile 的索引服务后，启动会执行本地 embedding 自检，尽早发现原生库或目录问题。
- 512 维模型标识与向量结构须一致。更新模型后用经测试的索引重建流程，不混用旧模型向量；模型输出还会检查有限值、维度等约束。

## 7. 后端构建与启动

### 7.1 Windows：推荐 Maven + 启动脚本

先设置 `JAVA_HOME` 为自己安装的 JDK 21，确认 `java -version`，不要依赖终端里碰巧排在最前面的其他 Java 版本。

```powershell
$env:JAVA_HOME = 'C:/Java/jdk-21' # 替换为本机实际路径
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
java -version
.\Server\mvnw.cmd -v
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp verify

# 先检查格式，再启动 database profile；本地模式改用 Server/.env.local。
.\Server\scripts\Start-Server.ps1 -EnvFile Server/.env -Check
.\Server\scripts\Start-Server.ps1 -EnvFile Server/.env
```

成功构建产物：`Server/target/fragpicker-server-0.1.0-SNAPSHOT.jar`。脚本默认读取此 JAR，启动参数为 `--spring.profiles.active=database`；测试后若修改源码，先重新构建再启动。按 Ctrl+C 停止。

在另一终端检查（本指南配置 `SERVER_PORT=18080`）：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
```

期望健康响应包含 `status: UP`。默认无详细配置输出；健康状态不证明转写或 AI 全链路已经完成。若不显式选 profile，`bootstrap` 只提供健康检查，不加载数据库业务接口；由于集成服务默认开启，bootstrap 也不是“无配置即可启动”的全功能模式。

### 7.2 后端 Gradle 与 IDEA

```powershell
.\Server\gradlew.bat -p Server clean build
# 使用 Gradle JAR 时显式传入产物；仍通过启动器加载本机 .env。
.\Server\scripts\Start-Server.ps1 -EnvFile Server/.env -JarPath Server/build/libs/fragpicker-server-0.1.0-SNAPSHOT.jar
```

IDEA 关联 **`Server/build.gradle`**，Gradle Distribution 选择 Wrapper，Gradle JVM 选 JDK 21。不要把 `.tools/gradle-8.13/bin` 关联成项目，否则会出现 “does not contain a Gradle build”。Maven 和 Gradle 二选一导入同一后端源码，避免重复模块；Android 单独关联。IDE 运行业务时添加 `database` profile 与进程环境变量，不使用 shell `.env` 自动加载的假设。

### 7.3 Linux：环境注入后运行 JAR

Linux/macOS 使用 `sh ./Server/mvnw` 与 `./Server/gradlew`；Maven 脚本在本基线没有可执行位，使用 `sh` 避免直接调用时的权限错误。以下以满足第 2 节原生库要求的 Linux 为例。`Start-Server.ps1` 是 Windows 启动器，下面直接注入环境变量：

```sh
export JAVA_HOME=/opt/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
sh ./Server/mvnw -f Server/pom.xml -B -ntp verify

export SERVER_PORT=18080
export DB_URL='jdbc:mysql://localhost:3306/fragpicker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export DB_USERNAME=fragpicker
export DB_PASSWORD='<replace-with-local-password>'
export JWT_SIGNING_KEY='<stable-base64-key-at-least-32-random-bytes>'
export PARSEVIDEO_BASE_URL='http://127.0.0.1:8001/'
export AI_CHAT_BASE_URL='https://api.deepseek.com'
export AI_CHAT_MODEL='deepseek-flash'
export AI_CHAT_API_KEY='<provide-via-local-secret-store>'
export OSS_ENDPOINT='oss-cn-shenzhen.aliyuncs.com'
export OSS_BUCKET='<your-private-bucket>'
export ALIBABA_CLOUD_ACCESS_KEY_ID='<provide-via-local-secret-store>'
export ALIBABA_CLOUD_ACCESS_KEY_SECRET='<provide-via-local-secret-store>'
export TINGWU_APP_KEY='<your-app-key>'
# 该目录应预先由部署者创建，并授予运行账号读写权限。
export INDEX_RUNTIME_DIRECTORY='/var/lib/fragpicker/runtime'
java -jar Server/target/fragpicker-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=database
```

尖括号内容是占位符，应由自己的秘密管理系统提供；现有进程中的开关也需核对，完整流程保持 12 项为 `true`。Linux 生成 JWT 可使用 `openssl rand -base64 32`。**不要直接 `source Server/.env`**：启动器模板的字面值规则与 shell 不同，例如 JDBC URL 的 `&`、空格、密码特殊字符会被 shell 解释。服务守护进程的工作目录、环境注入和目录权限需与上述手动启动一致。

## 8. Android 构建与安装

### 8.1 SDK 与 IDE

Android Studio 打开 `Android` 目录，选择 JDK 21 / Gradle Wrapper。在 SDK Manager 安装 Platform 36、Build Tools 35.0.0、platform-tools；设备测试准备 API 36 x86_64 模拟器。编译 SDK 36 不意味着 Build Tools 必须是 36；本项目未显式覆盖 `buildToolsVersion`，按 [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes) 使用默认 35.0.0。

命令行已安装 Android Command-line Tools 时，可按 [官方 sdkmanager 文档](https://developer.android.com/tools/sdkmanager) 安装（将工具加入 PATH）：

```powershell
$env:ANDROID_HOME = 'C:/Android/Sdk' # 替换为本机实际路径
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "emulator" "system-images;android-36;google_apis_playstore;x86_64"
sdkmanager --licenses
```

按提示阅读并接受所需许可。SDK 位置亦可写入忽略的 `Android/local.properties`，例如 `sdk.dir=C:/Android/Sdk`。选择 SDK 的平台版本和设备架构，不用 `local.properties` 保存云密钥。

### 8.2 Debug 构建与后端地址

```powershell
.\Android\gradlew.bat -p Android --version
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=http://10.0.2.2:18080' :app:assembleDebug :app:lintDebug
adb devices
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fragpicker.android/.MainActivity
```

APK：`Android/app/build/outputs/apk/debug/app-debug.apk`。以下地址区别很重要：

| 场景 | `API_BASE_URL` |
| --- | --- |
| Android 模拟器访问开发机 | `http://10.0.2.2:18080`；这是 Android 模拟器的主机映射，不是开发机服务绑定地址 |
| USB 真机 + 反向端口 | 先执行 `adb reverse tcp:18080 tcp:18080`，构建为 `http://127.0.0.1:18080`；设备连接断开后映射可能需要重建 |
| 真机 / 远程部署 | `https://your-backend.example.com`，使用有效 TLS 证书和实际后端地址 |

只写 `localhost` / `127.0.0.1` 而不配置反向端口时，指向的是设备自身。Debug 的明文例外仅允许 `10.0.2.2`、`localhost`、`127.0.0.1`；普通局域网 IP 的 HTTP 不在例外中。Release 不允许明文；不要为调试扩大为全局明文。

命令行 `-PAPI_BASE_URL=...` 可明确覆盖本机 Gradle 配置。该值写入 BuildConfig，修改后需要重新构建 / 安装，不会随后端 `.env` 修改自动改变。应用只持有用户登录会话，不内嵌数据库、阿里云或模型服务密钥。

### 8.3 Release 与签名

```powershell
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=https://your-backend.example.com' :app:assembleRelease
```

替换为自己的 HTTPS 根地址，不能带用户名密码、查询参数或 fragment。仓库未配置发布签名凭据；Release 产物需要自己的签名流程后再分发。Debug 使用本机 debug keystore；另一台机器生成的同包名 APK 未必可覆盖安装。为保留现有用户数据应使用原签名，不把“卸载后可安装”当作兼容升级验证。

部分液态玻璃效果受系统渲染能力限制，低 API 设备使用兼容路径；设备可运行与视觉效果逐像素一致是两个不同验收目标。

## 9. 测试与端到端验收

### 9.1 启动配置校验与普通后端测试

```powershell
.\Server\scripts\Test-StartupConfiguration.ps1
.\Server\mvnw.cmd -f Server/pom.xml -B -ntp verify
```

配置脚本使用合成值执行 9 项检查，覆盖完整开启配置、转写调优参数、本地模式、环境优先级、缺少凭据、依赖约束、短 JWT 密钥、重复键及非应用变量拒绝；不访问数据库或云服务。本文基线包含启动器 `TRANSCRIPTION_` 白名单修复，早于该修复的启动器会拒绝模板中的转写配置。

Maven 报告：`Server/target/surefire-reports`。缺少 `DB_TEST_URL` 或具体真实服务测试的 opt-in 变量时，对应测试跳过；核对 `tests / failures / errors / skipped`，不只看退出码。即使不配置云服务，包含本地 embedding 的测试仍要求原生库和目录可用。

### 9.2 真实隔离 MySQL 测试（Windows）

```powershell
.\Server\scripts\Test-MySql.ps1 -MySqlBin 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
# 验证后端 Gradle 路径时：
.\Server\scripts\Test-MySql.ps1 -MySqlBin 'C:/Program Files/MySQL/MySQL Server 8.0/bin' -Gradle
```

修改 MySqlBin 为实际目录。脚本在 `.tools/mysql-test-*` 新建隔离数据目录、随机回环端口和测试凭据，执行构建与真实 JDBC 测试，结束后关闭自己启动的实例。它不借用现有服务的数据目录；日志和测试目录保留用于诊断。它显式关闭外部服务流程，与生产默认 `true` 不冲突。

### 9.3 Android UI 与真实 HTTP 夹具

启动并确认一个 API 36 模拟器在线。先运行不依赖真实后端的选定 UI 回归：

```powershell
$uiTests = 'com.fragpicker.android.ProcessingCardTest,com.fragpicker.android.CalendarMorphTest,com.fragpicker.android.EmberBackgroundTest,com.fragpicker.android.ThemeRevealTest,com.fragpicker.android.LiquidHighlightClipTest'
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=http://10.0.2.2:18080' "-Pandroid.testInstrumentationRunnerArguments.class=$uiTests" :app:connectedDebugAndroidTest
```

再运行实际隔离 MySQL + HTTP 后端 + Android 页面夹具：

```powershell
$pageTests = 'com.fragpicker.android.HomeIntegrationTest,com.fragpicker.android.FeedIntegrationTest,com.fragpicker.android.HistoryIntegrationTest'
.\Server\scripts\Test-MySql.ps1 -MySqlBin 'C:/Program Files/MySQL/MySQL Server 8.0/bin' -AndroidAuth -AndroidKnowledgeFixture -AndroidTestClass $pageTests
```

脚本注入随机端口、测试账户及知识夹具；这些内容不是云转写生成的数据。报告在 `Android/app/build/reports/androidTests/connected` 与隔离实例日志中。测试构建会把 API 地址切为随机测试端口，结束后按第 8 节重新构建目标地址的 APK，再当作正常使用版本安装。

真实服务测试均为显式 opt-in，例如 `AI_CHAT_TEST_ENABLED`、`TINGWU_TEST_ENABLED`、`OSS_TEST_ENABLED`、`PLAYBACK_TEST_ENABLED`；解析服务测试使用 `PARSEVIDEO_TEST_BASE_URL`。其他业务真实测试还要求样本、账号或特定夹具，具体条件见 `Server/src/test/java` 各真实集成测试的启用条件和 [后端说明](Server/README.md)。这些测试开关不是生产服务开关，不建议一次性开启未知的全部 live 测试。

### 9.4 完整云端流程的人工验收

满足第 6 节真实凭据和配额要求后，以全部默认开启的业务配置启动。保存每一步的状态与错误码，不保存带签名的完整资源 URL / Token。

| 步骤 | 操作 | 验收依据 |
| --- | --- | --- |
| 注册 / 登录 | APP 注册后登录，或调用 `POST /api/v1/auth/register`、`POST /api/v1/auth/login` | 会话可用，重复用户名和错误密码按接口约定处理 |
| 投喂 | 输入自己有权使用、解析服务支持的视频分享链接 | 首屏反馈卡出现；`POST /api/v1/fragments` 接收 `shareText`、可选 `note`，并返回任务回执 |
| 任务推进 | 观察 APP 或 `GET /api/v1/fragments/{id}` | 异步任务推进；失败展示具体错误，不把解析成功等同于索引成功 |
| 内容 / 资源 | `GET /api/v1/fragments/{id}/content`、`/{id}/transcript`、`/{id}/media?kind=VIDEO` 或 `COVER` | 简明标题、简介、摘要要点、时间信息与媒体元数据可读取；签名资源可播放 |
| 检索 | `POST /api/v1/fragments/search`，如 `{"query":"导数相关学习资源","limit":5}` | 返回当前用户相关已索引内容；同义表达和不相关查询分别验证 |
| AI 对话 | 在对话页要求寻找一个已投喂视频 | Markdown 正常渲染，卡片对应实际相关来源，不夹带无关结果与“其余结果未纳入”等说明 |
| 首页 / 回顾 | `GET /api/v1/fragments?date=YYYY-MM-DD`、`GET /api/v1/calendar?month=YYYY-MM` | 今日与历史条目正确，默认收起日历、日期总结状态可区分 |
| 每日总结 | `GET /api/v1/daily-digests/{date}`；必要时 `POST /api/v1/daily-digests/{date}/regenerate` | 22:00 定时生成、次日补齐与手动请求分别验证；正文与统计分层展示 |
| UI / 生命周期 | 设置页切换主题、展开 / 收起卡片与日历、退后台再进入 | 主题圆形过渡、单次连续伸展、薪火上浮效果；后台暂停动画，进入前台恢复 |
| 剪切板 | 前台进入 APP 时复制受支持分享链接 | 按系统剪切板权限读取，预检查资源后询问用户；未确认不自动投喂 |

除了注册、登录、刷新和健康检查等公开入口，业务请求携带 `Authorization: Bearer <accessToken>`。账号只使用自己的测试内容；AI 工具按当前用户范围查询。API 参数、分页、重试与幂等细节见 [Server/README.md](Server/README.md)。

## 10. 完整配置项索引

下表覆盖 `application.yml`、`application-database.yml` 的应用环境变量及本地索引运行目录。未显式配置时使用所列源码默认；**必填**表示选定业务 profile / 启用对应服务后必填。时间量使用 `2s`、`5m`、`4h`、`24h` 等 Spring Duration 格式；字节量为整数。

### 10.1 基础、鉴权与聊天

| 变量 | 源码默认 | 含义 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口；本指南模板设 `18080` |
| `SCHEDULER_POOL_SIZE` | `8` | 调度线程池 |
| `DB_URL` | 必填 | `jdbc:mysql://...`，建议保持 UTC 连接时区 |
| `DB_USERNAME`、`DB_PASSWORD` | 必填 | MySQL 运行账号及密码 |
| `DB_POOL_SIZE` | `10` | Hikari 最大连接数 |
| `JWT_SIGNING_KEY` | 必填 | ≥32 随机字节的 Base64 值 |
| `JWT_ACCESS_TOKEN_TTL` | `15m` | 访问令牌有效期 |
| `JWT_REFRESH_TOKEN_TTL` | `30d` | 刷新会话绝对有效期 |
| `AI_CHAT_ENABLED` | `true` | 聊天客户端开关 |
| `AI_CHAT_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容根地址 |
| `AI_CHAT_API_KEY`、`AI_CHAT_MODEL` | 启用时必填 | 供应商密钥和实际模型 ID |
| `AI_CHAT_TIMEOUT` | `60s` | 单次模型请求超时 |
| `AI_CHAT_MAX_OUTPUT_TOKENS` | `4096` | 聊天模型输出上限 |
| `CHAT_TURN_TIMEOUT` | `180s` | 整个对话轮次超时 |
| `CHAT_MAX_CONCURRENT` | `2` | 对话轮次并发上限 |

### 10.2 分享解析与媒体下载

| 变量 | 源码默认 | 含义 |
| --- | --- | --- |
| `INGESTION_ALLOWED_HOSTS` | [配置中的多平台域名列表](Server/src/main/resources/application.yml) | 逗号分隔的受支持分享域名；与解析服务能力同步 |
| `INGESTION_WORKER_ENABLED` | `true` | 投喂解析工作器 |
| `INGESTION_POLL_DELAY`、`INGESTION_LEASE_DURATION` | `2s`、`5m` | 领取间隔、租约 |
| `INGESTION_MAX_ATTEMPTS`、`INGESTION_RETRY_BASE_DELAY` | `3`、`10s` | 尝试上限、重试退避基数 |
| `PARSEVIDEO_ENABLED` | `true` | 解析客户端 |
| `PARSEVIDEO_BASE_URL` | 启用时必填 | 解析服务根地址 |
| `PARSEVIDEO_USERNAME`、`PARSEVIDEO_PASSWORD` | 空 | 可选 Basic Auth，成对配置 |
| `PARSEVIDEO_CONNECT_TIMEOUT`、`PARSEVIDEO_READ_TIMEOUT` | `5s`、`45s` | 连接、读取超时 |
| `PARSEVIDEO_MAX_RESPONSE_BYTES` | `1048576` | 解析 JSON 最大 1 MiB |
| `MEDIA_TEMP_DIR` | `${java.io.tmpdir}/fragpicker-media` | 下载暂存目录；实际临时根由运行时准备 |
| `MEDIA_MAX_VIDEO_BYTES`、`MEDIA_MAX_COVER_BYTES` | `1073741824`、`10485760` | 下载最大 1 GiB / 10 MiB |
| `MEDIA_CONNECT_TIMEOUT`、`MEDIA_READ_TIMEOUT` | `5s`、`30s` | 下载连接、读取空闲超时 |
| `MEDIA_TOTAL_TIMEOUT` | `5m` | 下载总时限 |
| `MEDIA_MAX_REDIRECTS` | `3` | 经校验的跳转上限 |
| `MEDIA_WORKER_ENABLED` | `true` | 媒体保存工作器 |
| `MEDIA_WORKER_POLL_DELAY`、`MEDIA_WORKER_LEASE_DURATION` | `2s`、`30m` | 领取间隔、租约 |
| `MEDIA_WORKER_MAX_ATTEMPTS`、`MEDIA_WORKER_RETRY_BASE_DELAY` | `3`、`10s` | 尝试上限、重试退避 |

### 10.3 OSS、听悟与资源清理

| 变量 | 源码默认 | 含义 |
| --- | --- | --- |
| `OSS_ENABLED` | `true` | OSS 客户端 |
| `OSS_BUCKET`、`OSS_ENDPOINT` | 启用时必填 | 私有 Bucket 和地域 Endpoint |
| `OSS_MAX_VIDEO_BYTES`、`OSS_MAX_COVER_BYTES` | `1073741824`、`10485760` | 上传最大 1 GiB / 10 MiB |
| `OSS_SIGNED_URL_TTL` | `5m` | APP 媒体 GET 签名有效期 |
| `ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET` | 启用 OSS / 听悟时必填 | 阿里云身份凭据 |
| `ALIBABA_CLOUD_SECURITY_TOKEN` | 空 | 可选 STS 安全令牌 |
| `TINGWU_ENABLED` | `true` | 听悟客户端 |
| `TINGWU_APP_KEY` | 启用时必填 | 听悟应用 Key |
| `TINGWU_SOURCE_LANGUAGE` | `auto` | 原音频语言 |
| `TINGWU_CONNECT_TIMEOUT`、`TINGWU_READ_TIMEOUT` | `5s`、`30s` | SDK 超时 |
| `TINGWU_RESULT_TIMEOUT` | `60s` | 获取结果文件超时 |
| `TINGWU_MAX_RESULT_BYTES` | `16777216` | 结果文件最大 16 MiB |
| `TINGWU_SOURCE_URL_TTL` | `4h` | 提交给听悟的源视频签名有效期 |
| `TRANSCRIPTION_WORKER_ENABLED` | `true` | 转写工作器 |
| `TRANSCRIPTION_POLL_DELAY`、`TRANSCRIPTION_QUERY_DELAY` | `2s`、`1m` | 领取任务、轮询听悟状态间隔 |
| `TRANSCRIPTION_LEASE_DURATION`、`TRANSCRIPTION_MAX_TASK_AGE` | `5m`、`24h` | 租约、任务最大年龄 |
| `TRANSCRIPTION_MAX_FAILURES`、`TRANSCRIPTION_RETRY_BASE_DELAY` | `8`、`10s` | 失败上限、重试退避 |
| `MEDIA_CLEANUP_ENABLED` | `true` | 已删除内容媒体清理 |
| `MEDIA_CLEANUP_POLL_DELAY`、`MEDIA_CLEANUP_LEASE_DURATION` | `5s`、`10m` | 领取间隔、租约 |
| `MEDIA_CLEANUP_RESCAN_DELAY`、`MEDIA_CLEANUP_RETRY_BASE_DELAY` | `24h`、`5m` | 再扫描间隔、重试退避 |

### 10.4 知识概括、索引与检索

| 变量 | 源码默认 | 含义 |
| --- | --- | --- |
| `KNOWLEDGE_ENRICHMENT_ENABLED` | `true` | 知识概括工作器 |
| `KNOWLEDGE_POLL_DELAY`、`KNOWLEDGE_LEASE_DURATION` | `2s`、`5m` | 领取间隔、租约 |
| `KNOWLEDGE_MAX_ATTEMPTS`、`KNOWLEDGE_RETRY_BASE_DELAY` | `3`、`10s` | 尝试上限、重试退避 |
| `KNOWLEDGE_INDEX_ENABLED` | `true` | 本地 embedding 索引工作器 |
| `INDEX_RUNTIME_DIRECTORY` | 工作目录下 `.fragpicker-runtime` | 本地原生库、分词缓存、临时目录根；由 Java 运行时代码读取 |
| `INDEX_POLL_DELAY`、`INDEX_LEASE_DURATION` | `2s`、`10m` | 领取间隔、租约 |
| `INDEX_MAX_ATTEMPTS`、`INDEX_RETRY_BASE_DELAY` | `3`、`10s` | 尝试上限、重试退避 |
| `INDEX_MAX_CHUNKS` | `10000` | 单内容索引分块上限 |
| `SEARCH_MAX_CHUNKS` | `100000` | 搜索候选分块上限 |
| `SEARCH_TIMEOUT` | `15s` | 搜索时限 |
| `SEARCH_MIN_SIMILARITY` | `0.40` | 最低语义相似度 |
| `SEARCH_LITERAL_BOOST` | `0.10` | 字面匹配加分 |

### 10.5 每日总结

| 变量 | 源码默认 | 含义 |
| --- | --- | --- |
| `DIGEST_WORKER_ENABLED` | `true` | 每日总结生成工作器 |
| `DIGEST_POLL_DELAY`、`DIGEST_LEASE_DURATION` | `2s`、`5m` | 领取间隔、租约 |
| `DIGEST_SCHEDULE_ENABLED` | `true` | 22:00 / 次日补齐调度 |
| `DIGEST_SCHEDULE_POLL_DELAY` | `10s` | 调度扫描间隔，不是生成频率 |
| `DIGEST_REBUILD_COOLDOWN` | `60s` | 手动重建冷却 |
| `DIGEST_MAX_OUTPUT_TOKENS` | `8192` | 总结输出上限 |
| `DIGEST_JSON_OUTPUT` | `true` | 结构化 JSON 输出；需供应商支持 |

业务时区 `Asia/Shanghai`、Flyway 开关、Hikari 连接超时等是当前 YAML 固定配置，未伪造为额外环境变量。修改这些项时应连同测试与文档更新；受保护业务接口不公开环境变量详情。

## 11. 排错与复现实验记录

### 11.1 常见问题

| 现象 | 排查顺序 |
| --- | --- |
| IDEA 报 Gradle build 不存在 | 解除 Gradle `bin` 目录关联，改关联 `Server/build.gradle`，使用 Wrapper / JDK 21 后重新导入 |
| `.env` 已填但提示缺变量 | 确认通过启动器加载、`-EnvFile` 路径正确、没有旧进程环境覆盖；IDE / 直接 JAR 不自动加载 `.env` |
| 服务只响应 health，业务 404 | 确认启动 `database` profile，而非默认 `bootstrap` |
| 启动器 `-Check` 成功但启动失败 | Check 不做连接验证；查看实际 MySQL、私有 Bucket 权限、服务凭据、原生库启动自检 |
| `INDEX_INTERNAL_ERROR` / `UnsatisfiedLinkError` | 读取附近异常链及索引失败日志；查目录权限、磁盘、CPU 架构、ONNX 库 / DJL 缓存、Linux `ldd --version` 与 `GLIBC_2.34`；修复环境后通过项目重试流程重新索引 |
| MySQL 鉴权、时区或迁移错误 | 核对库名、授权来源、密码、UTC 连接参数、`flyway_schema_history`；TLS 关闭 / 公钥自动获取仅可用于隔离本机测试，不照搬到远程部署 |
| 解析 HTTP 成功仍失败 | 校对 `code=200` / `data.video_url` envelope、平台域名、资源时效、解析端 Basic Auth；纯图链接不是视频 |
| 下载被拒绝 / 代理环境不工作 | 确认资源域名解析到真实公网地址、跳转合规；Fake-IP 或内网资源应从网络配置解决 |
| APP 连不上 API | 区分主机端口与模拟器 `10.0.2.2`、真机反向端口、HTTPS 证书；检查 APK 内实际编译地址并重新构建 |
| 明文网络被禁止 | Debug 只有回环 / 模拟器地址例外；真机局域网或远程服务使用 HTTPS |
| APK 覆盖安装签名冲突 | 核对签名证书；沿用原签名以保留数据，不把卸载作为无损升级方法 |
| 依赖下载 / 原生库缺失 | 检查网络、代理与仓库访问；固定 Wrapper 和依赖版本，不依赖私有 `.tools` 缓存来宣称可重现 |
| 总结暂时未出现 | 核对业务日期、工作器开关、异步任务状态与 AI 能力；22:00 调度仍需要生成耗时，迟完成内容在次日补齐 |

### 11.2 实验记录模板

每次重现保存以下非敏感信息；真实样本内容与账号另行私有保存，不默认发布。

```text
代码提交 / 工作区是否有改动：
JDK 厂商、版本、OS、CPU 架构、glibc（Linux）：
Maven / Gradle / AGP / Kotlin / Android SDK 与设备 API：
MySQL 服务端版本、迁移版本、测试库 / 业务库区分：
依赖解析报告路径及 SHA-256：
parse-video 服务 commit / 镜像 digest / 响应契约：
OSS 地域、听悟服务配置（不包含凭据）：
聊天供应商、模型 ID、供应商公开的版本信息：
本地 embedding MODEL_ID、维度、运行目录：
样本文件 SHA-256、受支持平台、提交业务日期：
执行命令、开始 / 完成时间、退出码：
测试 total / passed / failed / errors / skipped 及跳过原因：
端到端各阶段状态、错误码、检索 / 播放验收结果：
JAR / APK SHA-256、APK 签名证书摘要：
```

```powershell
git rev-parse HEAD
git status --short
java -version
.\Server\mvnw.cmd -v
.\Android\gradlew.bat -p Android --version
mysql --version
# 另在实际 MySQL 连接中执行 SELECT VERSION();，客户端版本不替代服务端版本。
Get-FileHash Server/target/fragpicker-server-0.1.0-SNAPSHOT.jar,Android/app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

日志公开前隐去 API Key、密码、JWT、刷新令牌、视频转写正文和签名 URL。云服务测试使用自己的配置，不将历史聊天中的凭据加入源码、模板、日志或 README。

### 11.3 开发交付规则与相关文档

每个可独立验证的模块单独完成 `git status` → `git diff` → 检查 / 测试 → `git add`（仅该模块）→ `git diff --cached` → `git commit` → `git push`；push 成功后继续下一模块，不提交无关本机配置，不 force push 覆盖历史。

- [项目规划与交付进展](docs/PROJECT_PLAN.md)
- [快速启动与本机启动器说明](docs/QUICK_START.md)
- [Server 接口、参数与逐模块验证](Server/README.md)
- [Android 页面与设备验证记录](Android/README.md)
- [0.1.0 历史预览验收记录](docs/PREVIEW_RELEASE.md)

**完成重现的判据**：记录固定代码与环境，构建和本地测试可再次执行；真实外部服务逐项验收；明确注明跳过项与未完成项。只有本地测试通过时，结论写为“本地重现通过”，不扩大为“完整云端流程已验证”。
