# FragmentsPicker Android

Kotlin + Jetpack Compose + Material 3；最低 Android 8.0（API 26），编译和目标 SDK 36。使用固定的稳定依赖版本，应用主题支持浅色、深色和跟随系统，外观偏好通过 DataStore 保存。

在 Android Studio 打开本目录，或使用 Java 17+ 与 Android SDK 36 构建：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Android SDK 路径通过本机 `ANDROID_HOME` 或忽略的 `local.properties` 提供。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。在已连接的模拟器上执行 `:app:connectedDebugAndroidTest`，验证启动、主题切换和重建后偏好保留。

已交付应用壳、主题、真实账号登录与注册页面。投喂和知识页面按独立模块继续接入。应用不包含阿里云、聊天供应商、数据库的凭据，所有云服务由后端调用。

## 登录与后端地址

Debug 默认使用 Android 模拟器宿主地址 `http://10.0.2.2:18080`；将本机后端的 `SERVER_PORT` 配置为18080，并启用 `database` profile。也可通过构建参数 `-PAPI_BASE_URL=https://your-backend` 改地址，支持必要的路径前缀。发布构建必须显式配置 HTTPS 地址，否则构建失败。明文 HTTP 仅在 Debug 对模拟器宿主与 loopback 开放，Release 禁止明文流量。

登录使用已有后端 `/auth/login` 和 `/users/me`，错误密码与网络失败有界面提示，提交期间禁用重复操作，密码不保存到偏好或重建状态。访问令牌仅在内存；刷新令牌用 Android Keystore AES-GCM 加密后保存，密文绑定当前后端地址，备份和迁移禁用。进程重启通过一次性刷新恢复会话，刷新串行执行；发送前删除旧令牌，无法确定是否成功的请求不重放，需重新登录。

## 真实登录集成测试

连接 API 26+ 模拟器并设置 `ANDROID_HOME`、`JAVA_HOME`，从仓库根目录运行：

```powershell
.\Server\scripts\Test-MySql.ps1 -AndroidAuth
```

测试脚本在随机 loopback 端口启动新的隔离 MySQL 与后端，自动把地址传入 Android 构建，验证错误密码、成功登录、页面重建、Keystore 加密和刷新恢复，最后关闭自己的测试进程。它不修改现有 MySQL 服务。普通 `connectedDebugAndroidTest` 未指定 `realBackend=true` 时跳过真实后端登录测试，仍可执行主题与 Keystore 测试。

## 注册页面

登录页面的“创建账号”进入注册；用户名、密码长度与后端约束一致，确认密码只在本机校验，不发送到后端。用户名冲突提示修改用户名，网络中断时提示结果未确认并允许返回登录。注册成功后显示确认页，点击“前往登录”预填用户名，密码需要重新输入；密码不保存到偏好或重建状态。上面的真实后端测试同时覆盖密码不一致、大小写用户名冲突、创建账号及用新账号登录。
