# Task 6 消费准备

2026-10-12只读核对；不表示正式制品或消费者已经通过。执行以implementation-plan.md和权威规格为准。

## 正式制品入口

- `scripts/verify-distribution.sh`先向独立临时file仓库deploy当前release模块，再用空consumer-m2解析发行ZIP、生成archetype、复制仓外consumer源码并verify。它拒绝target、符号链接和仓库绝对路径泄漏，并比对部分消费JAR/POM与发布字节。初次deploy已有`-DskipTests`不能当全仓测试结果；完整测试仍单独完成。
- 该脚本`MVN`接收可执行文件，不接收带参数的命令字符串。若隔离build-cache需小型临时wrapper，逐参数转发，不能改消费者独立仓库参数；不能复用旧同坐标Fibra SNAPSHOT作为正式输入。
- 脚本退出会删除自己创建的临时目录。正式验收前需明确保留日志、XML、发布/消费SHA和原生退出码的方式，不能事后把不存在的临时路径当可复核证据。
- `scripts/verify-client-packages.sh`运行npm测试及boundary lint，校验声明基线，pack两个正式包并建立独立consumer；`FIBRA_NPM_PACK_DIR`可指向一个新的空目录保留本轮tarball。隔离Tela的vendor必须使用本轮tarball及SHA，不混入旧同名包。

2026-10-12主线程已用Node22.22.2/pnpm11.19.0运行该npm门禁，nativeExit0：12项测试、boundary lint、声明基线、正式pack及仓外消费通过。28个源输入和tarball SHA在`/private/tmp/fibra-config-contract-evidence/npm/receipt.json`；产物位于`/private/tmp/fibra-config-contract-npm.g4QgkR`。API包SHA `de07c38c5413c1648389554144049f6d4dc0f89ceeb7ea0f9156b280997f27ac`，protocol包SHA `b41a942ef9c8990532dbc8dd7b313052572f8d5bf97f9334b19299570aad51a5`。本结果不替代后续Tela实际import/create或新增消费断言；若相关源改变须重建重新绑定。

已补仓库锁定Node22.14.0的同一完整npm门，nativeExit0，28项源输入未变，两个tarball SHA与上述完全相同。正式后续消费优先使用`/private/tmp/fibra-config-contract-npm-node22.14`；收据与原生退出码归档`../../references/plugin-config-contract/implementation/npm-node22.14/`。Node官方压缩包与HTTPS发布SHA列表一致，未执行独立签名验证；未更改仓库版本配置。

## 隔离 Tela 输入

已创建`/private/tmp/tela-config-consumer.7vs6zn1a/tela`，全部476源文件输入锁在相邻`source-inputs.json`；复制后逐项SHA一致。来源和非干净提交边界见progress.md。此阶段未写任何Tela源码，未运行其Maven/npm。

| 实际作者 | 已核代码行为 | 本轮结构合同必须注意 |
| --- | --- | --- |
| `tela-model-api` | descriptor和入口均声明`tela-model-api`，Config为Map，bootstrap传空Map | 虽名为API模块，仍有真实definition，不能写空名称集合 |
| `tela-model-deterministic-plugin` | 声明`tela-model-deterministic`；当前start不读config，bootstrap传空Map | 依据本包真实使用定义规则，不能套用Fibra四个空入口API包 |
| `tela-agent-plugin` | validator要求非null Map与非空白dataRoot；ProductStorePath读取dataRoot，start才打开存储 | 结构要求dataRoot字符串；minLength不能冒充非空白/路径或DB可用保证 |
| `tela-model-openai-plugin` | start读取model、可null baseUrl、可null requestTimeoutMillis；后者默认60000并要求转换后的long为正 | schema不注入默认；整数/long上界在结构门明确，API key来自环境，不加入实例schema或测试输出 |
| `agent-view` client | 真实create不读config；bootstrap传空Map，activate创建root并注册scope清理，stop卸载root | 用当前产品真实入口验证import/create/activate/stop；不为了数字测试增加产品字段 |

具体nullable及封闭对象收紧由实现结合现有binder和全部调用点确认，并在迁移说明记录；不为历史宽松输入添加兼容decoder。

## 保留原测试语义并补实际执行

- `tela-distribution/.../TelaJavaDefinitionConsumptionTest`直接调用Java driver，已有真实四作者JAR静态目录/prepare及私有声明不一致测试。此层不自动经过Engine schema门，旧validator失败断言应保留，不能把测试全替换为更早缺schema失败。
- `tela-host/.../client/TelaClientCatalogIntegrationTest`现有脚本故意在import时报错，手动ACK只证明目录与Host协议路径。新增配置查询可扩展此处；该ACK不能当本轮实际client执行证明。
- `tela-desktop/test/renderer-load.test.mjs`已经bundle真实`client-runtime.ts`，在VM用真实Blob字节经`SourceTextModule`执行import，跟踪scope、URL及生命周期。可沿同一harness加载实际agent-view构建产物，供给有限TelaUi/TelaReact宿主能力，核真实create/activate/stop和资源释放；明确这不是Electron Desktop实机验收。
- `module-loader.ts`将assignment.config原样交给ClientInstanceContext。精确数字应沿既有tagged decimal合同验证；不能强行转换为JS Number或修改wire。`9007199254740993`另用边界fixture验证，不塞入没有该字段的agent-view或sanitizer。
- Node真实进程测试应沿现有runtime start配置并调用功能，再stop/close；schema通过和JSON.parse数字精度是两项保证。
- 宿主错误输出仅取安全诊断字段。新canary应同时覆盖未知键、敏感值和外来异常，不序列化EngineChangeException.view()整个target。

## 完成证据

记录精确作者源/JAR/包根SHA、Maven发布与消费仓库、npm tarball SHA、完整适配后输入锁、命令原生退出码及测试XML。真实恢复/CAS、旧宽新严不保存、静态发现无执行、失败关闭和真实Node/client执行各有独立断言。不得更新Tela P3状态或宣称其产品整体已验收。
