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

当前骨架可以在没有数据库和云凭据的情况下运行。后续数据库模块启用后，相关 profile 的必填环境变量会在启动时验证；业务能力以各模块交付为准。

## 配置约定

`application.yml` 中的凭据只引用环境变量。测试 AppKey、Bucket 和 AccessKey 同样通过环境变量配置，不提交真实值。

| 环境变量 | 用途 |
| --- | --- |
| SERVER_PORT | HTTP 端口，默认 8080 |
| PARSEVIDEO_BASE_URL | 已部署的视频解析服务地址 |
| TINGWU_APP_KEY | 听悟应用 AppKey |
| OSS_BUCKET / OSS_ENDPOINT | 私有对象存储 Bucket 与 Endpoint |
| ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET | 阿里云访问凭据 |
| AI_API_KEY | 百炼 API Key |
| AI_CHAT_MODEL / AI_EMBEDDING_MODEL | 百炼聊天与向量模型名称 |

本模块只声明外部配置占位符，尚未调用这些服务。设置环境变量后重启进程生效。真实凭据、`application-local.yml`、`.env`、构建输出与签名文件均在 Git 忽略列表中。
