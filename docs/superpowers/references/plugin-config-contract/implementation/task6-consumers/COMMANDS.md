# 最终命令

JDK=`/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home`；Maven=`/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`；PATH前置该JDK/bin、`/private/tmp/fibra-config-contract-node22.14/node-v22.14.0-darwin-arm64/bin`、`/Users/sunke/.nvm/versions/node/v22.22.2/bin`（pnpm11.19.0）。

外部消费者（cwd Fibra，但 Maven -f/multiModuleRoot 均指外部源码）：
```sh
mvn -o -B -ntp --settings /private/tmp/fibra-config-contract-evidence/consumers/external/settings.xml \
 -Dmaven.repo.local=/private/tmp/fibra-config-contract-evidence/author/distribution/run/consumer-m2 \
 -Dfibra.repository.url=file:///private/tmp/fibra-config-contract-evidence/author/distribution/run/remote \
 -Dfibra.version=0.5.0-SNAPSHOT -Djunit.version=6.1.3 -Dspring-boot.version=4.1.0 \
 -Dconsumer.evidence=/private/tmp/fibra-config-contract-evidence/consumers/actual-output \
 -f /private/tmp/fibra-config-contract-evidence/consumers/external/pom.xml clean verify
```

以下cwd=`/private/tmp/tela-config-consumer.7vs6zn1a/tela`，共同参数：
```sh
mvn -o -B -ntp --settings /private/tmp/fibra-config-contract-evidence/consumers/tela-settings.xml \
 -Dmaven.repo.local=/private/tmp/fibra-config-contract-evidence/consumers/tela-m2 \
 -Dfibra.repository.url=file:///private/tmp/fibra-config-contract-evidence/author/distribution/run/remote
```

完整构建追加 `-DskipTests package`，r3在已批准非sandbox环境运行，以保持pnpm store一致。首次解析缺失依赖的r1没有`-o`，其编译错误另见REPORT。

最后聚焦追加：
```sh
-Dexec.skip=true \
-Dtest=TelaJavaDefinitionConsumptionTest,TelaClientCatalogIntegrationTest,TelaHostGatewayErrorTest,TelaClientRuntimeTest,TelaHostClientShutdownIntegrationTest \
-Dsurefire.failIfNoSpecifiedTests=false \
-Dtela.catalog.packaged.artifacts=/private/tmp/fibra-config-contract-evidence/consumers/tela-author-jars \
-Dtela.config.wire.archive=/private/tmp/fibra-config-contract-evidence/consumers/tela-client-snapshot.json test
```
此处exec.skip仅不重复执行此前已经成功的Desktop install/build/test与distribution打包命令；Java所有指定测试实际运行，原生XML为34项。

Renderer cwd=`.../tela/tela-desktop`：
```sh
TELA_CONFIG_WIRE=/private/tmp/fibra-config-contract-evidence/consumers/tela-client-snapshot.json \
node --experimental-vm-modules --test test/renderer-load.test.mjs
```

日志统一重定向到本目录对应`.log`；每次真实命令退出立即写`.exit`，不以tail/tee退出码代替原生结果。最终采集 `python3 collect.py` 仅复制并核对现有证据。
