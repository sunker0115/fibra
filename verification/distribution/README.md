# 仓库外分发验收

本 fixture 不属于 Fibra reactor。`scripts/verify-distribution.sh` 先把二十七个发布制品部署到临时文件仓库，再从空 Maven 本地仓库构建这里的 core、真实 Java 插件 JAR、通过公开 API 的真实多插件协作、Engine 和 Spring Boot 消费场景，最后调用已部署 archetype 生成并构建独立插件项目；外层脚本还会从 ZIP 公开启动器执行该 Java 插件贡献的动态 CLI 命令及 help，停用插件后再次调用并验证命令已经消失。

该门禁验证仓库外消费者能直接解析并调用已发布的插件制品；Windows 的原生 DACL 与 `ReplaceFileW` 调用不在此非 Windows fixture 中执行，不能将其视为实机 Windows 验证证据。
