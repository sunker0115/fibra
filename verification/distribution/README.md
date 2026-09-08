# 仓库外分发验收

本 fixture 不属于 Fibra reactor。`scripts/verify-distribution.sh` 先把十二个发布制品部署到临时文件仓库，再从空 Maven 本地仓库构建这里的 core、真实 Java 插件 JAR、Engine 和 Spring Boot 消费场景，最后调用已部署 archetype 生成并构建独立插件项目。
