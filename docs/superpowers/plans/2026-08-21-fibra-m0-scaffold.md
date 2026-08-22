# M0：工程基线交付记录

状态：已完成

## 交付物

- Maven 父工程 `com.sstlfsj:fibra:${revision}`，`revision` 是全仓唯一项目版本真源。
- `fibra-core` Java 21 模块，包根 `com.sstlfsj.fibra`。
- 运行时依赖：Reactor Core 3.8.6、SLF4J API 2.0.18。
- 测试依赖：JUnit 6.1.3、Reactor Test 3.8.6、Awaitility 4.3.0。
- Maven Compiler Plugin 3.14.1、Surefire 3.5.4。
- Flatten Maven Plugin 1.7.0 在安装和发布时把 `${revision}` 展开为真实版本。

## 约束

- 所有异步公共契约统一使用 Reactor `Mono`/`Flux`/`Publisher`。
- `fibra-core` 不绑定日志实现，由宿主提供 SLF4J provider。
- PF4J 只属于未来插件装载适配层，不进入内核。
- 所有类型和文档统一使用 `Fibra`/`fibra`，不再使用旧名称。
- 第三方依赖、内部模块和 Maven 插件版本统一由父 POM 的 `properties` 与 `dependencyManagement` 管理；子模块不声明版本。

## 验收

```bash
mvn verify
```

该命令必须完成编译、测试和打包，不能跳过测试。
