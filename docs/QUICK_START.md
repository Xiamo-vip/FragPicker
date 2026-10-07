# FragPicker 可用版本启动

当前版本提供账号、分享投喂、处理状态与人工重试、日历回顾与每日总结、语义检索和 AI 对话、详情/原文/播放器、删除及设置。云服务凭据稍后配置时，解析后的任务会等待媒体阶段；不会自动完成转写或产生真实视频知识。不要把等待中的记录当作已经整理好的资料。

## Android

安装交付的 `FragPicker-preview.apk`，Android 最低为8.0（API 26）。这是使用开发签名的可安装预览版，升级时需要同一签名；正式签名发布随后单独处理。APK 连接当前工作目录配置的 `https://api.fragpicker.xiamoi.top`。首次使用创建账号，然后登录；设置页切换主题或退出。投喂支持粘贴与系统分享，回顾页选择日期，对话页查找已完成整理的内容。

自行构建：

```powershell
$env:JAVA_HOME = '<本机 JDK 21 目录>'
.\Android\gradlew.bat -p Android '-PAPI_BASE_URL=https://api.fragpicker.xiamoi.top' :app:assembleDebug
```

输出为 Android/app/build/outputs/apk/debug/app-debug.apk。真机使用局域网 HTTP 地址时不能绕过应用网络限制；请提供 HTTPS 后端。模拟器联调可用 `-PAPI_BASE_URL=http://10.0.2.2:18080`。

## Windows 后端

需要 JDK 21 与 MySQL 8.0/8.4。先用本机管理员执行 Server/scripts/init-local-mysql.sql，按注释单独建立 fragpicker 账号及授权；脚本不会清空已有数据库。表结构由 Flyway 自动迁移到 V14。

1. 将 Server/.env.example 复制为 Server/.env；填写本机数据库密码、稳定的 JWT_SIGNING_KEY。凭据文件被 Git 忽略，不要提交。
2. 保留现有 AI_CHAT_API_KEY 环境变量，或在本机 .env 填写。聊天模型示例使用已验证的 deepseek-flash，可替换成供应商实际支持的模型。
3. 在仓库根目录执行下面命令。已有进程环境变量优先于 .env；值按字面读取，不执行表达式，也不支持行末注释。Java 进程启动后修改变量需重启生效。

```powershell
$env:JAVA_HOME = '<本机 JDK 21 目录>'
.\Server\mvnw.cmd -f Server/pom.xml verify
.\Server\scripts\Start-Server.ps1 -Check
.\Server\scripts\Start-Server.ps1
```

单独生成 JWT 密钥的示例：`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`。生成一次后保存在本机配置，同一部署保持不变。

已有 JAR 可以用 `-JarPath '<JAR 的绝对路径>'`，配置文件可用 `-EnvFile '<本机 .env 的绝对路径>'`。Check 只验证变量与工作器依赖，不代表连通性成功。启动后访问 http://localhost:18080/actuator/health 确认 UP；默认 bootstrap 只提供健康检查，启动脚本固定启用真实业务的 database profile。

Linux 使用同一 JAR，向服务进程注入 .env.example 所列环境变量，再运行 `java -jar fragpicker-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=database`。Android 的 HTTPS 地址需由反向代理转到该服务；SSE 路径 `/api/v1/chat/` 关闭代理缓冲并允许至少240秒读取超时。

## 稍后启用云链路

示例默认关闭 OSS/听悟，解析、对话、增强、索引和每日总结的工作器分别配置。云凭据就绪后，将 OSS_ENABLED、MEDIA_WORKER_ENABLED、TINGWU_ENABLED、TRANSCRIPTION_WORKER_ENABLED、MEDIA_CLEANUP_ENABLED 都设为 true，再重启；已有排队任务会继续处理。需要正确的私有 Bucket、Endpoint、AccessKey 和听悟 AppKey，不在 APK 保存任何云凭据。重新提交不确定的云转写前会要求确认可能产生的费用。

真实 OSS/听悟→转写→入库→安卓播放验收按用户要求延期。现有服务健康检查不代表它已更新到本次源码；部署时应替换 JAR 并重启。已签名媒体地址过期时重新获取，删除后不再向客户端生成播放地址。
