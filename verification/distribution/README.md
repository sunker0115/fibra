# 仓库外分发验收

本 fixture 不属于 Fibra reactor，也不进入正式 Fibra 产品包。`scripts/verify-distribution.sh` 先把二十七个正式发布制品部署到本次运行专属的临时文件仓库，再构建这里的 core、真实 Java 插件 JAR、通过公开 API 的真实多插件协作、Engine、`CliSession` 嵌入和 Spring Boot 五类消费场景，最后调用已部署 archetype 生成并构建独立插件项目；外层脚本还会从 ZIP 公开启动器执行该 Java 插件贡献的动态 CLI 命令及 help，停用插件后再次调用并验证命令已经消失。

`engine-application` 的数据驱动生命周期消费者分别以 Java 和 Node 插件运行时验证公开 Registry 生命周期：探测、安装（不得自动启用）、启用并调用、完整配置更新、停用后重新启用、升级、相同字节的重复升级、相同版本但内容改变的升级、存在进行中调用时的空完整 deploy、重新安装，以及停用后卸载。验证也检查运行时及既有插件制品在本机 Maven 仓库中的主 JAR/POM 与临时发布仓库按字节一致。

`LifecycleEntrypoint` 仅是 `verification/distribution/java-plugin` 中供该消费者构造和验证 Java 插件的测试夹具入口；它不属于正式 Fibra 包或对外产品 API。Node fixture 要求本机已配置可执行的 `node`，脚本仅使用 `node:fs` 和 `node:readline` 等 Node 内建模块，不执行 `npm install`，也不访问网络。

脚本所有 Maven 阶段统一复用本机 `~/.m2`，不创建空的 Maven 本地仓库，也不清除其中的 Fibra 坐标。`remote_repository` 仅是本次运行接收 `deploy` 制品的临时发布目标；隔离 settings 用于限制远程解析来源。消费者仍在复制出的仓库外目录构建，并核对本机缓存与临时发布目标中的正式制品字节一致。

该门禁验证仓库外消费者能直接解析并调用已发布的插件制品；Windows 的原生 DACL 与 `ReplaceFileW` 调用不在此非 Windows fixture 中执行，不能将其视为实机 Windows 验证证据。
