# FragmentsPicker Android

Kotlin + Jetpack Compose + Material 3；最低 Android 8.0（API 26），编译和目标 SDK 36。使用固定的稳定依赖版本，应用主题支持浅色、深色和跟随系统，外观偏好通过 DataStore 保存。

在 Android Studio 打开本目录，或使用 Java 17+ 与 Android SDK 36 构建：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Android SDK 路径通过本机 `ANDROID_HOME` 或忽略的 `local.properties` 提供。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。在已连接的模拟器上执行 `:app:connectedDebugAndroidTest`，验证启动、主题切换和重建后偏好保留。

当前交付为应用壳与主题，账号、投喂和知识页面按独立模块继续接入。应用不包含阿里云、百炼、数据库的凭据，所有云服务由后端调用。
