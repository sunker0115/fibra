# 制品与隔离消费者影响面

2026-10-11完成范围核对，2026-10-12用户批准设计后进入实施。对应已独审正文 SHA-256 `3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`；本清单说明影响面，不是验收结果。首次核对时未修改生产/POM/签名或Tela副本，后续状态见progress.md。

## Fibra 直接影响

| 范围 | 已定位的真实文件/入口 | 批准后的预期变化 |
|---|---|---|
| 依赖及签名 | 根pom.xml、fibra-config/pom.xml；docs/api/fibra-config-public-signatures.txt、fibra-artifact-public-signatures.txt、fibra-engine-public-signatures.txt | 按规格新增库及公开合同；不混入平台依赖升级 |
| Java作者源 | fibra-plugins下12个正式插件的pom.xml和src/main/resources/META-INF/fibra/plugin.yaml | 每包新增唯一src/plugin/fibra-config.json，按真实definition/config绑定，构建复制为JAR META-INF/fibra/config.json；原名称描述仍是真源 |
| 正式发行 | fibra-distribution/pom.xml的stage-formal-plugin-packages、src/assembly/bin.xml、src/main/distribution/plugins | 从各正式JAR提取合同到包根；校验作者源/JAR/包根字节一致；不另手写12份根规则 |
| archetype | fibra-plugin-archetype/src/main/resources/archetype-resources/{pom.xml,src/assembly/plugin.xml,README.md}；META-INF/maven/archetype-metadata.xml；FibraPluginArchetypeIT、projects/basic/verify.groovy | 模板包含schema作者源并装配；现metadata只显式包含src/plugin/fibra-package.yaml，必须补新文件入口；生成后验证JSON的$schema/$ref未被模板过滤破坏 |
| Node真实消费者 | fibra-example/content-sanitizer/node-plugin/{fibra-package.yaml,payload/index.mjs}及同例Spring host | 同包根静态合同、实际进程接收配置；结构门与Node Number表示边界分别验收 |
| 仓外作者/宿主 | fibra-distribution/src/test/consumers/{java-plugin,engine-application,runtime-provider-application,cli-application,spring-boot-application}及README | 只消费正式制品，动态作者全包QA、按需查询、共同预检、保存状态和关闭反例，不用源码reactor替代 |

12个现有正式包：fibra-fs、fibra-fs-local、fibra-tool-fs、fibra-tool-fs-search；fibra-subprocess、fibra-subprocess-local；fibra-shell、fibra-shell-local、fibra-tool-shell；fibra-storage、fibra-storage-json、fibra-tool-storage。根据真实definition逐个核合同，API型模块也不能凭名称推断无配置或无definition。

Builtin变更限新configSchemas与implementationDigest输入、框架派生唯一有效digest；保留packageDigest读取。需要迁移的builder调用与fixtures在实现前全仓精确搜索，不把所有packageDigest读取误改成implementationDigest。

## Tela 隔离消费范围

本次已准备 `/private/tmp/tela-config-consumer.7vs6zn1a/tela`，不是Git工作树；来自旧参考副本 `/private/tmp/tela-definition-consumer.f6x_d0l_/tela`。来源记录HEAD为 `3b5c3579df43b3a47d5a1069e095a6d126a2c604`，但不能据此称副本等于该提交：原清单471项中8项已被此前适配修改，另有5个新增源文件，无缺失。差异清单和最初15个关键输入摘要见 [consumer-inputs.json](consumer-inputs.json)。新副本相邻`source-inputs.json`锁定全部476源文件，复制后SHA逐项一致；未复制target/node_modules/dist/.git，尚未进行本次适配或测试。正式验收还须锁定适配后的全部输入、正式依赖与脚本并保留输出，不能用本来源锁替代正式消费证明。准备发现见[consumer-preparation.md](consumer-preparation.md)。

不修改Tela主树、不恢复P3生产目标、不增加产品配置UI或组合机制。隔离消费拟限以下位置：

| 范围 | 隔离副本文件 | 必要目的 |
|---|---|---|
| 4个Java作者包 | tela-model-api、tela-agent-plugin、tela-model-deterministic-plugin、tela-model-openai-plugin：各pom.xml及新增src/plugin/fibra-config.json | 按既有真实输入类型写合同，同源装进实际发行JAR；其中agent/openai的发行物带plugin classifier，不能提取错JAR |
| Java发行装配 | tela-distribution/pom.xml、src/main/assembly/bin.xml | 从正式4个JAR取合同并装配各包根，原源码根仅已有fibra-package.yaml，不额外维护另一份规则 |
| 产品client合同 | tela-desktop/plugins/agent-view下唯一作者源，scripts/build.mjs；tela-distribution/src/main/assembly/bin.xml | 当前client产物放到包内client/，schema必须另装到tela-agent-view包根；不能只复制进client目录后依赖fallback |
| Host消费测试 | tela-host/src/test/java/com/sstlfsj/tela/host/client及runtime；公开FibraEngine query/Apply | 覆盖配置发现、旧宽新严、保存前拒绝、恢复、错误DTO和owner关闭；不新增产品Gateway路由来证明Java API |
| client实际执行测试 | tela-desktop/test/{renderer-load,lifecycle}.test.mjs；已有runtime/module-loader.ts、client-runtime.ts与plugins/agent-view/src/index.tsx作为真实被测输入 | 使用正式npm制品实际import/create/配置/关闭；不改wire、不增加runner、不用ACK替代执行 |
| 发行验证 | tela-distribution现有测试与正式ZIP | 合同文件存在且字节/精确revision一致；Host集成与Desktop实机结果分别记录，不用前者宣称后者通过 |

当前TelaClientRuntimeProvider.builtInPackages返回空列表，因此未发现本副本需要迁移builtin builder；不预先新增无用builtin fixture。后续全仓核出真实使用再纳入同一明确范围。

## 当前门状态

设计审查已通过，2026-10-12用户明确批准公共API/依赖/制品及builtin破坏性迁移范围。进入实现，正式制品与消费者验证仍待完成；Tela主树保持只读，隔离验证仍限本文列明范围。具体命令和结果随实施记入progress.md，不把此前来源核对当本次正式消费者通过。
