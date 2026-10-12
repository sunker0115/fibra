# 通用配置合同设计：生命周期 R2 复核

2026-10-11。审查文件 `docs/superpowers/specs/2026-10-11-plugin-config-contract-design.md`。

**SHA-256：`0792890247fd0f1289351e3ce5c481f5e6e7bc3bc41f928f5ff895ed020b4eb8`**。

完整读取当前规格，复用并核对先前读取的源码接缝；只读、未改仓库、未执行测试。Nowledge针对搜索只返回已有external assignment配置投影记录，未用其代替本次生命周期证据。

## 结论

**B0 / I0 / M2。原 I1/I2/M1 均已闭合；没有新增需要重开架构的生命周期问题。** 两处小修是表述精度与作者QA清理示例边界，给出可直接替换的最小文字。此结论只针对本轮生命周期范围，不替代组合profile库资格、全设计其它审查或用户材料性批准。

## 原问题关闭证据

### I1 已关闭：精确资源字节绑定及 public record 能力边界

- 第 217 行已固定从原根digest读取的相同chunk派生raw SHA-256/长度，原两snapshot核对，无新增打开，无原包摘要算法变更。
- 返回字节必须匹配冻结事实；find后改写/截断/替换及“R1缺失后新增”明确拒绝。当前责任限于固定资源，没有扩大成目录原子快照或恶意ABA保证。
- 第 219 行把public builder手造record限定为metadata，新方法无核验事实即无I/O拒绝、不fallback；toBuilder只有内容身份/root不变保留，state/timestamp可保留，身份改动丢弃，equals/hashCode不把可重建证据当第二身份。这与Store.record与Transaction.save现接缝匹配。
- CC02 第 312 行及第 219 行验证清单覆盖find后变化、手造/toBuilder、重启、mtime及原digest不变。其反例能证伪此前path-only迟读的错误实现。

### I2 已关闭：读资源owner与公开失败事实分离

- 第 255 行明确读取attempt仅为私有资源owner，不增FailureSubject变体。
- 第 259–263 行完整区分query、新target Apply、同target Apply重建、BOOTSTRAP、Reconcile/requestRecompile。
- 第 265 行使用原failStop(fact)结束部署operation并关闭准入；query不把历史Apply改FAILED；普通PLANNING与cleanup RELEASING分开，operationStage仍PLANNING；BOOTSTRAP显式PRESENT+BLOCKED；shutdown在新operation前重放缓存close，不覆盖首错或释放依赖。
- 这直接修正此前沿catalogFailStop只enterFailStop导致operation仍RUNNING的反例。既有FailureSubject/FailureFact/EngineOperationSnapshot字段足以实现，不需要第二状态机。
- CC07 第 317 行现要求五类路径字段级检查，与第 265 行的read+close双失败、安全code、cleanup优先结合，验收具备可证伪性。

### M1 已关闭：健康reconcile没有伪称比较fingerprint

- 第 251 行明确健康/PENDING reconcile仅协调已经验证的既有代，不重捕输入、不发现外部文件变化。
- Apply、显式重编译、需要重建的reconcile才进入deploy重捕身份并适用原no-op fingerprint。配置门仍保证所有新candidate输入重验，避免为了schema重启无关实例。

## 两处小修

### M1：第 217 行的派生事实应明确非regular不同于缺失

第 215 行已经拒绝非regular/symlink，但第 217 行只枚举“raw SHA-256/长度或缺失事实”。现有包树允许普通目录，因此包根存在名为 `fibra-config.json` 的目录仍是可表达输入；它不能在派生事实中被折叠成ABSENT。无需新增对抗能力，只补状态措辞。

**最小替换：**

> 在原根digest遍历中记录固定路径的缺失、非regular或regular文件事实；对regular文件从同一读取chunk计算raw SHA-256及长度。缺失与非regular分开，非regular在固定资源读取时明确拒绝，不当作缺失或后来新增。

现有错误码可将非regular归固定资源READ_FAILED，不需要增加公共类型；如果主线程已有统一非法制品码，也可明确保持该原码。此建议不要求目录/符号链接新扫描，沿已有树遍历分类即可。

### M2：第 77 行作者QA的finally关闭应区分资源close成功/失败

作者QA直接调用record固定资源owner，未经过Engine共享attempt字段；“读取并关闭后…finally关闭Engine/store”容易被照抄为资源close失败后仍无条件关闭Engine/store，或者重复关闭Engine已拥有的store。完整运行设计第 223/265 行已有更严格owner保证，此处应加一个条件以避免文档消费者示例违背它。

**最小替换末句：**

> 作者QA在外层持有固定资源句柄，真实close成功后才释放引用；常规完成或读失败但close成功时由Engine关闭其已接管的store，不重复独立关闭。资源close失败则保留句柄及其依赖、明确令QA失败并进入宿主终止清理路径，不继续无条件关闭依赖；未移交给Engine的资源由原构造owner关闭。

这只是让必须交付的作者消费示例遵守现有关闭合同，不新增平台级终止协议。测试可对该外层调用者注入一次close失败，核依赖close计数与失败句柄仍可达；不需要扩大到全部文件系统防护。

## 未发现新增偏差的范围

- preflight仍先于所有candidate create/prepare，并消费候选exact record，不反向调用公开query排队；版本/CAS/保存状态没被新增资源证据替换。
- 单schema延期与全局中立预算分开，没有因ConfigSchemaSource构造而把dormant语义提前编译；同包先读取整个文件并不等于强制编译所有schema。
- builtin digest明确包含facetId、profile及schema，集合排序固定；不会因仅schema变化沿旧持久selection静默重启。旧目标恢复BLOCKED的破坏性迁移仍公开列出。
- §9 保留真实EngineChangeException.view、明示Throwable图不承诺零配置，同时只允许安全错误投影，没有用null view或schema旁路破坏现消费者。
- 新增组合/typed-map/numeric runtime界限仍有单独未完成资格门，本轮未将“设计上有预算公式”当作库成本证明。

建议在整稿定稿时加入以上两处文字，再按最终hash归档审查；这两处不要求新增公共API或扩大实现范围。

## 最终修文定点复核

2026-10-11。最终规格 SHA-256：`3a52766a420058ce35087078e84de3686a2728b4d7a69eea51b3918cd76e217e`。本次仅核指定三处修文及相邻合同，不重开完整调查。

- **本轮 M1 已关闭**：第 219 行明确记录缺失、非regular及regular三类事实；非regular在固定资源读取时独立以READ_FAILED拒绝，不折叠为缺失。与第 217 行既有读取限制一致，没有引入额外文件系统对抗承诺。
- **本轮 M2 已关闭**：第 79 行明确作者QA持有句柄直到真实close成功；Engine关闭已接管的store，原构造owner关闭未移交资源；close失败保留句柄和依赖，令QA失败并进入宿主终止清理，不无条件继续关闭依赖。正式示例须覆盖该反例，与共享owner的运行时合同一致。
- **指定安全补文无新增问题**：第 291–293 行将additionalProperties接受的动态键视为输入，把错误定位截断到最近静态声明的字典父路径，明确不输出动态键或其后完整路径；CC10覆盖键自身含secret canary及嵌套对象/数组。该条补足未知字段规则之外的合法动态键泄露路径，并保留第 297 行授权管理snapshot与安全错误投影的明确边界。

**最终结论：此次定点范围 B0 / I0 / M0。原 I1/I2/M1 及 R2 的 M1/M2 均已关闭；未发现三处修文引入的新偏差。** 这是设计文字审查，未运行测试，未改动仓库文件，也不替代实现及库资格门的后续证明。
