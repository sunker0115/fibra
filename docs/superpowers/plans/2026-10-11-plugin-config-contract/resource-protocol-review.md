# 固定配置资源协议只读审查

结论：主线程候选可以采用，不需要增加公开 exception、enum、Optional 或状态访问器。`NoSuchElementException` 仅表示「已核 revision 的固定资源事实为 ABSENT」，`ArtifactException` 表示读取失败；Engine 必须在精确 `resource.read()` 边界投影。此次未修改仓库、未运行 Maven。

## 依据与边界

- 批准规格 §6（约 217–225 行）已要求原 snapshot 派生 ABSENT/NONREGULAR/REGULAR、无 I/O 创建、手造 record 拒绝、重复 read/close 观察实际终态。§7 的执行顺序不要求新增 public failure 分类。
- `ArtifactException` 现有公开信息只有 `phase()` 和 `path()`，构造器为包级。`ArtifactPhase` 只有 VALIDATE/DIGEST/STAGE/COMMIT/RECOVER，不能靠 phase 无歧义地区分「原缺失」与「后来读取失败」。无须修改现有公开异常 API。
- `PluginPackage.digest/updateFile` 已从同一流读取 chunk，适合顺带收集固定资源 raw hash/length；`Snapshot.sameContent` 当前只核 manifest、包 digest 和 facets，需要把新派生事实也纳入两次 snapshot 核对。原 digest 输入必须保持不变。
- `PluginPackageStore.record` 是构造已核 record 的集中位置；find/restart 的 `readStoredRecord` 已重新读包并核 pluginId/version/revision，可以在此重建派生事实。不能把 metadata builder 所填内容当核验事实。
- `RuntimeDefinitionQuery` 已有「read/close 各推进一次、缓存实际终态、close 等 read、关闭前未读则拒绝后续 read」约定；`PluginPackageStore.close` 也缓存关闭失败且不重试物理关闭。

## 必须落实的分类

| 原核验事实 | read 行为 | Engine 投影 |
| --- | --- | --- |
| ABSENT | 不查询当前 FS，抛固定安全文本的 `NoSuchElementException` | MISSING_CONTRACT |
| ABSENT，之后新增文件或目录 | 仍按 ABSENT | MISSING_CONTRACT |
| NONREGULAR（现有合法包实际可覆盖目录） | 不尝试将当前文件当新规则，安全 `ArtifactException` | READ_FAILED |
| REGULAR，现文件缺失/截断/替换/不可读/symlink | 安全 `ArtifactException`；不得把 `NoSuchFileException` 转为缺失信号 | READ_FAILED |
| REGULAR，同字节但 mtime 改变 | 有界读取并核 raw hash/length 后返回防御副本 | 正常继续解码 |
| 手造 record 无核验事实 | `configurationResource()` 立即 `IllegalStateException`，无 I/O | 调用前置条件失败，不能冒充 MISSING_CONTRACT |

原 snapshot 的 NONREGULAR 不意味着允许把 symlink/FIFO 导入包；现有 `validateTree` 对 symlink/特殊文件的拒绝继续成立。NONREGULAR 可以由固定文件名处的目录证伪。

## 候选所需的必要明确化

1. 在新 `read()` Javadoc 写出 `NoSuchElementException` 仅用于绑定 revision 中原本不存在的固定资源。它虽不新增 public 类型/方法，但属于新 public 方法的行为合同，必须同步规格和验收，不能成为未记录的类型暗号。无 cause、无 path/用户值的固定文本足够；不采用 null byte[] 或零字节数组哨兵，二者会与非法实现/空合同混淆。
2. Engine 仅包住 `resource.read()` 捕获该类型。不得把 create、runtime 名称读取、envelope 查询、schema 编译或整个 attempt 外层所有 `NoSuchElementException` 一并映射为缺失。Engine 不读取 FS、不检查 message、不从 ArtifactException.phase 猜缺失。
3. 新资源产生的 ArtifactException 使用固定 message、null path、null cause；禁止保留 suppressed 原异常或透出原异常类名/message。参数 `path` 可为 null，现构造器没有 nonnull 限制。非regular/读 I/O 用 VALIDATE，hash/length 不符用 DIGEST 都与现代码含义相容；Engine 统一 READ_FAILED，不依赖此分配。close 错误仍可使用现异常类型，其 CLEANUP_FAILED 投影由关闭操作上下文决定，毋须新增 phase。
4. 「成功关闭清除原异常引用」不能清掉可重放的安全读终态。应分别保存 read 终态与 close 终态：已成功 read 后 close 再 read 返回新防御副本；已失败 read 后成功 close 再 read 重放相同安全失败；close-before-read 则固定拒绝。原 IOException/第三方异常不缓存。安全异常本身可缓存，成功 close 清除 stream 及原始异常引用即可。
5. 最小同步状态机即可覆盖同步资源：read 与 close 在同一互斥边界推进，read 期间 close 等真实结束；流一经返回立即存入该句柄再执行任何可能失败操作；不在 read 内 try-with-resources 偷先关闭。close 首次标记 attempted，失败保留 stream 并缓存安全 close 失败，后续 close 不再次物理关闭。Engine 仍负责登记句柄后才调 read，并在清理失败时保留 attempt 对该 owner 的可达引用。
6. 派生证据只经 package-private/private 路径注入 record；public builder 不暴露 setter，不执行路径补核验。toBuilder 保留事实的条件在 build 时检查最终规范化 location、pluginId/version/revision、managedPackage 身份与原绑定一致；只改 state/updatedAt 可保留。改变 managed facets 等内容身份也必须使能力失效，不能只比包 revision 字符串。equals/hashCode 不含事实。

## 最小验收建议

- ABSENT 后创建有效文件仍 MISSING；NONREGULAR 后替换为普通文件仍 READ_FAILED。
- REGULAR 后删除、截断、同长度改字节、symlink 替换均 READ_FAILED；同字节 mtime 变化成功；返回数组被改后重读不受影响。
- 同一资源并发/重复 read 只打开一次；close 等阻塞 read 结束；读失败后可 close；close 失败只关闭一次且 stream 仍可达；重复 close 重放关闭失败。
- read 成功/失败之后 close 成功，再 read 仍重放各自读终态；close-before-read 不打开文件且后续 read 拒绝。
- 手造 record 即使 root/revision 看似正确也立即拒绝；toBuilder metadata 变化保留，绑定身份变化失效；find 重启重建成功；原 digest 不变。

检索说明：已通过 Nowledge MCP 作一次针对本合同的检索，未命中直接决策；仅使用当前规格和源码推导结论。图工具未提供，且无法确认独立浏览器与检索相同身份/Space，因此未打开检索图。未将近似记忆当作本合同依据。
