# Task2 固定配置资源独立质量审查 R1

结论：PASS，B=0 / I=0 / M=0。未发现本轮范围内需要修复的正确性、资源生命周期、安全诊断、维护性或意外公开 API 问题。此结论仅关闭 Task2 质量门，不关闭 Engine 集成、发行签名或总目标。

日期：2026-10-12。仓库 `/Users/sunke/dev/ai-project/fibra`，分支 `codex/plugin-config-contract`，基线 `0a674176b8b762b4e77044bcba7f051bf06c41d9`。全程只读仓库；没有 commit/push、Tela 修改或仓库源码/测试修改。

## 输入绑定

- 实算实现者 `source-sha256.json` SHA-256 为 `2c10b97defb8942e24068a71fafaf87e01f7a07ac80641eeb7dd54da6c119c79`；其 10 个生产/测试文件逐项与当前源码相同。
- 本轮输入清单：`quality-review-r1-input-sha256.json`，SHA-256 `91b55629aca4c29a515d330c3898c424d061da04ad2afeeab35dc53dee2c7711`。含上述 10 文件、ManagedPluginPackage/ManagedFacet/PluginFacet 三个关键依赖、权威规格与 resource-protocol-review，共 15 文件。
- 结束前主线程在规格 §1 追加用户“不用兼容代码”的既定边界；已重新读取该段并重绑规格 hash。生产与测试输入未变，§6 资源合同未变；最终清单 15 项已再核对一致。
- 直接阅读真实 tracked diff、全部新增生产文件和三个新增测试文件、PluginPackageTest 新增用例与独立摘要 helper，以及 Store 的 prepareInstall/readStoredRecord/save/record 路径。先前 spec 报告仅作为运行证据索引，不代替质量判断。

## 质量核对及代码定位

1. **单一事实源与兼容边界**：PluginPackage.java:273–353 在原 root digest 同一打开流/读取 chunk 上派生固定文档事实；原 frame、长度、排序与 `fibra-content-v1` 输入保留，facet 摘要不采集该事实。新增 ContentDigest/Evidence 都是内部不可变值；Store 持久字段集合未变。未新增旧格式解析、旧 API 转发、旧摘要回退、路径补核验或兼容分支。保留原 metadata builder 属既有合法用途，不是新增兼容层。
2. **能力注入完整**：PluginPackageStore.java:427–440 是唯一生产 verified 注入调用；prepareInstall 采用最终 owned object 的 frozenPackage，readStoredRecord 重新读完整包并核身份，save 的 state/time 转换沿既有 toBuilder。PluginPackageRecord.java:18–36/41–49/65–84 将证据私有绑定最终规范化 root、pluginId、version、revision、完整 ManagedPluginPackage。后者的 equals 包括完整 ManagedFacet/PluginFacet，immutable lists 防止事后修改。builder 无证据 setter；先改后恢复最终身份仍可保留，最终身份变更则拒绝能力。equals/hashCode 未新增第二身份。
3. **资源状态机清晰**：FixedPluginConfigurationResource.java:46–113 的 read/close 同步于一个 monitor；stream 在 open 返回后立即归 input，后续分配和 hash 失败仍能由 close 清理。read 内没有隐含关闭。读成功或安全失败均可在 close 后回放；close-before-read 固定拒绝。关闭失败设置终态且保留 input，重复 close 不再次物理关闭。当前少量显式状态足以表达真实终态，无额外 executor/cache/registry。
4. **边界与安全错误**：FixedPluginConfigurationResource.java:28–35 拒绝 exact root 与固定文件的 symlink/nonregular；实际 open 亦使用 NOFOLLOW_LINKS。67–93 的读取最多消耗 1 MiB+1 个探测字节，仅 ≤1 MiB 成功，返回前验证 raw digest 与长度。ABSENT/NONREGULAR 无后验文件查找；所有读取/关闭异常只保存安全 enum/boolean，异常实例每次重建，null path/cause，无原异常或外来 message/cause/toString 访问；非致命 Error 安全映射，VME/ThreadDeath 先保存失败状态再重抛。防御 clone 防止调用方污染成功终态。
5. **公开面保持最小**：只新增 PluginPackageRecord.configurationResource 和 public PluginConfigurationResource.read/close。派生事实、具体 owner、I/O 注入 seam、verified 工厂均非 public；没有新依赖、公开异常分类、路径参数或状态访问器。接口 Javadoc 包含 ABSENT 语义、惰性关闭及终态回放，record Javadoc 包含核验前置条件。

## 测试真实性及验证边界

- 真实文件测试通过 store.prepareInstall/save/find 取得 record，再实施删除、截断、同长度改写、替换、目录、symlink、mtime 修改；不是直接填 evidence 后声称证明 Store。重开 Store 覆盖证据重建，metadata builder 等值/同 hash 用例证明能力与 metadata 身份分离。
- 摘要回归使用测试侧独立 `digest(Path)` 实现比较当前包摘要，且断言新增/修改合同改变包摘要但不改变 facets；双快照 hook 中修改配置被拒绝。
- 同步测试用 latch 阻塞实际 stream.read；并发 close 在释放前未完成，最终核对 read 结果、唯一 open 与唯一 close。流保留测试检查真实 input 引用；安全失败断言覆盖 path、cause、suppressed 和固定消息。Error 测试使用可控 InternalError/ThreadDeath，不制造真实 OOM。
- 仓库 1 MiB+1 用例是已知 evidence 超限的提前拒绝，不单独证明实际增长流读取上限。现有独立 spec 探针针对该不同路径已有实证，也覆盖恶意异常 getter、外部 suppressed 污染、读成功后关失败和 exact root symlink。本轮已查阅其实际命令/exit/output，不将其误称为永久 JUnit 覆盖。
- 上一独立 spec 审查已在同一 10 文件 hash 上重新编译并执行 88 tests，零 failure/error/skip，四组边界 probe 通过。本轮没有发现需要新反例验证的疑点，因此没有重复 Maven/整套测试，也没有声称自己重新运行这 88 项。
- 本轮 `git diff --check -- fibra-artifact` 实际 exit=0。输出物只在 `/private/tmp`，没有任何本轮启动的 Maven/javac/java 或后台验证进程；Maven 写权限可立即交还。

未将 hostile ABA、全目录原子快照、CPU SLA 或 Engine 兄弟资源清理扩大为本轮要求。没有当前 scope 内的待关闭缺陷；后续 owner 集成与正式 API signature 门仍需各自证据。

## 检索说明

按隔离子 agent 路由执行 Nowledge MCP memory_search，query=`fibra plugin configuration artifact fixed resource contract`、limit=5（服务返回 strict/default）。返回旧 Fibra 泛匹配，无本合同直接决策；未用其作为审查依据，也未继续抓取历史。未提供 explore_graph 工具，无法确认浏览器同一 owner/Space，所以未展开记忆图。Codex 本地 MEMORY.md 关键词检索无直接相关命中，未使用本地记忆事实。
