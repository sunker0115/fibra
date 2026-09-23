# Fibra 发布与构建基线

## 发布边界

当前 release workflow 的正式发布集合是 29 个 Maven 制品：

- 版本目录：`fibra-bom`，仅发布展开后的 BOM POM；
- 基础层：`fibra-api`、`fibra-core`、`fibra-config`、`fibra-artifact`；
- 托管与扩展层：`fibra-engine`、`fibra-bridge`、`fibra-runtime-java`、`fibra-runtime-node`、
  `fibra-registry`；
- 宿主与集成层：`fibra-cli-api`、`fibra-cli`、`fibra-spring`、`fibra-spring-boot-starter`；
- 开发与协议：`fibra-plugin-archetype`、`fibra-client-protocol`；
- 正式插件产品：`fibra-tool-api`、`fibra-fs`、`fibra-fs-local`、`fibra-tool-fs`、
  `fibra-tool-fs-search`、`fibra-subprocess`、`fibra-subprocess-local`、`fibra-shell`、
  `fibra-shell-local`、`fibra-tool-shell`、`fibra-storage`、`fibra-storage-json`、
  `fibra-tool-storage`。

其余 28 个 Maven 制品发布主 JAR、sources JAR、Javadoc JAR 和展开后的 POM；`fibra-bom` 是纯 POM
制品，不生成空 JAR。根工程、聚合模块、acceptance、distribution、example、parity 和 benchmarks 不发布。

同时发布 2 个 npm 制品：

- `@sstlfsj/fibra-client-api`，无运行时依赖；
- `@sstlfsj/fibra-client-protocol`，只依赖完全相同版本的 client API。

npm tarball 只包含 `package.json`、`LICENSE`、`NOTICE` 和 `dist/`。两者只承载 transport-neutral 契约，不包含
浏览器端执行器、Web 资源装载器、前端渲染框架绑定、连接实现或产品 fixture。

各模块 POM 中显式的 `<maven.deploy.skip>false</maven.deploy.skip>` 是 Maven 发布集合的唯一真源，
`scripts/release-maven-modules.sh` 只负责读取该声明。分发、可复现构建与 release workflow 都消费同一结果；
脚本同时断言当前发布边界仍为 29 个模块。`ArchitectureBaselineTest` 保证 `fibra-bom` 管理的 28 个坐标恰好
等于其余正式发布制品，且不导入第三方 BOM。修改发布边界时必须同步模块 POM、BOM、数量断言与本文。

## Package 与插件发布物

Maven JAR 与可安装 package 是两个层次。12 个动态插件发布各自 Java JAR；发行 ZIP 再把 JAR 放入以
`fibra-package.yaml` 为根清单的完整目录 package。package 是安装、摘要、持久化和恢复的原子单位，可包含
多个 facet。每个 facet 独立声明 runtime、execution target、payload、facet 依赖和能力要求。

Java JAR 内的 `META-INF/fibra/plugin.yaml` 只描述 Java 本地定义和可选 entrypoint。contract-only JAR 使用
空 descriptor，不复制逻辑 package 身份。宿主可见契约依赖使用 `provided`；JNA、Jackson 等实现依赖只能按
对应插件的受控打包策略进入实现 JAR，不能传递到宿主。

Node payload 必须已包含可执行入口和生产依赖。runtime 不执行 `npm install`，不联网补依赖。市场、下载、
签名、信任和上传鉴权属于宿主策略，不属于 Fibra package 协议。

## 权威门禁

所有门使用 Java 21、Maven 3.9.9、`client/.node-version` 锁定的 Node.js 22.14.0、pnpm 11.19.0、Python 3、
`unzip`、Bash 和 ripgrep 15.0.1。该版本同时满足 pnpm 11.19.0 的 Node.js 22.13 engine 下限和 npm Trusted
Publishing 的 Node.js 22.14 下限；Maven/runtime、client、完整发布和 npm publish workflow 不得各自维护
不同 Node 版本。

```bash
mvn --batch-mode --no-transfer-progress clean verify

corepack enable
corepack prepare pnpm@11.19.0 --activate
pnpm --dir client install --frozen-lockfile
scripts/verify-client-packages.sh
scripts/verify-reproducible-release.sh
scripts/verify-architecture-boundaries.sh
```

`scripts/verify-distribution.sh` 不在本地最终验收中执行；push 和正式 release 的 GitHub Actions 自动调用它。

门禁职责：

- `clean verify`：全 reactor 单元、契约、真实 Java/Node runtime、重启、插件组合、Spring、archetype、API
  签名和 JMH 编译；
- `verify-client-packages.sh`：TypeScript declaration 基线、严格归档成员、版本关系和离线 tarball 消费者；
- `verify-reproducible-release.sh`：28 组主 JAR、sources、Javadoc，29 个 Maven POM，以及发行 ZIP 与完整目录
  树的字节复现；
- `verify-distribution.sh`：部署到临时文件仓库，并在复制出的独立目录验证 core、Engine、外部
  `RuntimeProvider`、Spring Boot、archetype、CLI ZIP 和真实插件，同时检查 28 个 Maven 主 JAR 的内容边界与
  BOM POM 的仓外解析来源；
- `verify-architecture-boundaries.sh`：拒绝旧 runtime/package 模型重新进入当前生产源码、POM 与发布入口。

独立消费者必须只从临时发布仓库或 npm tarball 解析制品，不得借用 reactor classpath、workspace 链接或源码
目录。仓库外 runtime provider 消费者是公开 SPI 的硬门，不可用本仓私有测试实现替代。

## CI

`.github/workflows/ci.yml` 分两层：

1. `maven-verify` 复用同一套锁定的 Java、Maven、Node 与 ripgrep 环境，运行完整 Maven reactor、超时诊断、
   架构边界和 Maven/发行 ZIP 可复现门；
2. `npm-verify` 构建、测试、边界检查并消费两个 npm tarball。

空仓分发验证下载量和耗时较高，本地开发与 PR 事件不执行；每次 push 由 GitHub Actions 自动执行，正式
release workflow 也执行一次。每次执行内部只建立一个全新消费者仓库，不再二次清仓重跑。

所有 action 使用完整提交 SHA。Maven、ripgrep、Node 和 pnpm 版本由仓库锁定；门禁不能依赖开发机全局安装
的偶然版本。GitHub Actions 通过固定提交的 `pnpm/action-setup` 读取 `client/package.json` 的
`packageManager`，不依赖 Node 发行包内置的 Corepack 版本。

## 正式发布

发布通过 `.github/workflows/release.yml` 手动触发，`workflow_dispatch` 的运行 ref 必须选择与输入完全相同的
发布标签，确保 npm provenance 中的 `GITHUB_REF/GITHUB_SHA` 就是实际打包源码。输入标签必须是
`v<major>.<minor>.<patch>`，或带字母开头 qualifier 的预发布版本；根 POM revision 必须是相同的非
`SNAPSHOT` 版本，且标签提交必须位于 `main` 历史中。两个 npm package 的版本必须与根 revision 完全相同。

`verify-release` 在标签提交上重跑 Maven、npm、可复现、架构边界和一次空仓分发门，并保存本次已验证的 npm
tarball。通过后：

- `publish-central` 使用 `central-release` profile 对严格 29 个 Maven 模块签名并上传 Central Portal；
- `publish-npm` 在支持 Trusted Publishing 的 Node/npm 环境中下载并发布上述不可变 tarball，通过 OIDC 生成
  provenance；不重复 build/test/pack。稳定版使用 `latest`，预发布版使用 qualifier 首段作为 npm tag。

Central 与 npm 发布只消费同一个已验证提交。Central profile 保持 `autoPublish=false`，上传后必须在 Portal
人工核对 GAV、POM 元数据、附件和签名。仓库不保存 Central、GPG 或 npm 长期凭据。

## 发布前检查

发布负责人应确认：

- 根 revision、Git 标签、两个 npm version 和 29 个 Maven POM 一致；
- package manifest、API/declaration 基线、共享协议 fixture 和独立消费者已随契约变化更新；
- 发行 ZIP 中每个标准插件都有 `fibra-package.yaml`，并从受管 package 恢复；
- 正式归档不含 verification、fixture、产品 client 实现或旧模型名称；
- Central Portal 与 npm 上的制品集合、签名、provenance 和发布标签符合预期。
