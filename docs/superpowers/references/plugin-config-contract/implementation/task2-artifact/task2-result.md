# Task2 artifact 固定配置资源 — DONE，待主线程独立审查

基线：0a674176b8b762b4e77044bcba7f051bf06c41d9；分支 codex/plugin-config-contract。
本阶段仅修改 fibra-artifact：6 个生产文件（3 个新增），4 个测试文件（3 个新增）。无 commit/push，无其他模块或 Tela 修改，无依赖/签名基线更新。

## 实现合同

- PluginPackage 原 root digest 遍历的同一 chunk 派生固定 fibra-config.json 的 ABSENT/NONREGULAR/REGULAR(raw SHA-256,length)；不额外打开文件，不改变 fibra-content-v1 前缀、排序、frame 或输入；两次 snapshot 同时比较证据。
- Store.record 经 package-private verified 构造注入绑定；find/restart 依既有重新读包路径重建，无持久字段。
- public builder 无证据，configurationResource 立即无 I/O 拒绝；toBuilder 在最终 build 以规范 root、pluginId/version/revision、完整 managedPackage equality 核对保留。metadata state/time 可变；原 equals/hashCode 不变。
- 新 public interface 仅 read()/close()；创建无 I/O。ABSENT 不查文件系统，只给固定安全 NoSuchElementException；NONREGULAR 直接安全失败。REGULAR 拒绝非普通文件/symlink，实际读取上限 1 MiB+1 个探测字节，仅最多 1 MiB 成功返回，并匹配原 hash/length。
- 同一 synchronized 边界使 close 等待实际 read；只物理 open/read/close 一次。stream 在 open 返回后立即存入 owner，read 不提前关流。失败 close 保留 stream、安全失败分类且不重试；成功 close 清 stream，保留防御副本/安全读终态。安全分类每次产生新异常，避免外部给异常添加 suppressed 污染后续重放。
- IOException/RuntimeException/非致命 Error 转固定安全 ArtifactException，null path/cause，无 suppressed；原异常不缓存。VirtualMachineError/ThreadDeath 先记安全终态再原样抛出；仅类型检查，不读取 foreign message/cause/toString。

## 验证证据

实际命令和 nativeExit 见 commands.json；run.sh 固定 JDK21/Maven3.9.9、独立 /private/tmp/fibra-config-contract-m2，离线 -pl fibra-artifact -am test。没有缺包补齐或其他 Maven writer。

- red-bootstrap.log：1 项，exit1，新公开接缝缺失 NoSuchMethod。
- red-behavior.log：1 项，exit1，已核 record 尚无资源能力。
- red-error-terminal.log：1 项，exit1，非致命 Error 错误透出。
- red-fatal-state.log：2 项，exit1，致命错误后重复 read 错误返回 NPE。
- green-final.log：88 tests，0 failures/errors/skips，exit0。分布：PluginPackageTest 46、PluginPackageStoreTest 15、PluginConfigurationResourceTest 16、FixedPluginConfigurationResourceTest 10、bootstrap 1。
- git diff --check exit0。

覆盖：R1 find 后缺失/截断/同长变化/文件替换/目录/symlink；原 missing 后新增；原非regular后替换；mtime不变语义；1MiB边界；手造metadata无权；toBuilder最终identity/metadata恢复；reopen find；原digest与facet不变；防御副本；重复/并发read只open一次；read-close双失败；close等待阻塞read；close-before-read；读失败流可达；关失败不重试；成功关释放stream并重放读终态；非致命和致命错误终态。

最终GREEN之后仅新增两处方法级 @SuppressWarnings("removal")（沿既有ThreadDeath门面）及 interface Javadoc澄清；按主线程指令未为这些无行为变化重跑测试。最终源摘要包含这些文字/注解改动。

## 摘要与边界

source-sha256.json 含10文件 SHA-256；该清单自身 SHA-256：2c10b97defb8942e24068a71fafaf87e01f7a07ac80641eeb7dd54da6c119c79。
完整本阶段 diff：task2.patch，SHA-256：f72a6e33251d23b800a63096b6d75a6abd3816b8367315078d83badc5f43c4cf。

无已知未关闭实现 blocker。没有宣称全目录原子性或 hostile ABA；Engine登记句柄、兄弟清理、父owner保留留给后续owner测试。公开signature baseline属于Task7。本结果为Task2局部门，不代表总目标完成。

自审已完成，当前停止编辑仓库和执行 Maven，交主线程独立spec/quality审查。
