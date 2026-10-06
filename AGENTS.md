# FragmentsPicker 开发规则

## 项目边界

- 产品名称 FragmentsPicker，简称 FragPicker。
- 后端在 Server 下实现，使用 Java、Spring Boot、MyBatis-Plus、LangChain4j、MySQL。
- Android 在 Android 下实现，使用 Kotlin、Jetpack Compose、Google Material 风格。
- application.yml 的敏感配置引用环境变量；禁止把真实密钥写入源码或提交。
- 项目规划与已确认需求位于 docs/PROJECT_PLAN.md。用户已确认要求与未指定的工程参数应区分。
- 首版个人自用，用户名密码登录，支持 parsevideo 多平台；使用百炼，必须实现语义检索。
- 视频和封面长期保存到私有 OSS；北京时间每天 22:00 总结，次日补齐，不推送。

## 用户要求的 Git 交付规则

每完成一个可独立运行、可验证的功能模块，立即检查、测试、提交并 push 到 https://github.com/Xiamo-vip/FragPicker.git。

模块包括独立页面、接口、业务功能、前端组件、数据库功能、登录、注册、上传、AI 对话、独立重要 Bug 修复和明确重构。

严格按以下顺序执行：

1. git status
2. git diff
3. 检查代码并测试当前模块
4. git add 当前模块相关文件
5. git diff --cached
6. git commit
7. git push

push 成功后才能继续下一个模块。禁止连续完成多个模块后统一提交。不要擅自提交无关的已有改动；不要 force push 覆盖历史。测试或 push 失败先处理并告知用户。

文档模块执行内容与格式检查；功能模块必须完成与功能相应的验证。仅模拟外部服务的测试不代表已完成真实集成验证。
