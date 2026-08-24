# ${artifactId}

这是由 `com.sstlfsj:fibra-plugin-archetype` 生成的独立 Fibra 插件工程，不继承 Fibra parent，也不依赖 Fibra 源码仓库。

```text
plugin-api   共享 Greeting 契约和 contract-only 插件包
plugin-impl  typed config 与 executable 插件包
config       可直接装载的 Fibra 配置
deployment   插件与配置的联合 deployment 包
```

执行：

```bash
mvn verify
```

产物：

```text
plugin-api/target/${pluginId}-contract-${version}.zip
plugin-impl/target/${pluginId}-${version}.zip
deployment/target/${pluginId}-deployment-${version}.zip
```

Fibra、PF4J、Reactor 和共享 contract 均为 `provided`，不会复制进 executable 插件的 `lib/`。`plugin.properties` 不含 `Plugin-Class`；插件业务生命周期只由 Fibra 管理。

deployment 的 `checksums.sha256` 用于校验内容完整性，不证明发布者身份。生产分发仍需由宿主或制品平台验证签名和来源。
