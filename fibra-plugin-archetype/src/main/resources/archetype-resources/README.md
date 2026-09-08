# ${artifactId}

这是一个独立的 Fibra Java 插件工程。

```bash
mvn verify
```

构建产物 `target/${artifactId}-${version}.jar` 可通过 `fibra-registry` 安装到显式指定的 `java` runtime。
插件入口由 `META-INF/fibra/plugin.yaml` 唯一声明，不使用类扫描、PF4J descriptor 或 extension index。
