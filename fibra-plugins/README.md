# Fibra 正式插件

`fibra-plugins` 是正式插件产品的根聚合模块，不是可部署制品。领域目录下的聚合 POM 也只负责组织
Maven reactor；真正安装到 Fibra 的是各 contract、provider 和 consumer JAR。

## 模块角色

| 角色 | 本仓库制品 | 作用 |
|---|---|---|
| 宿主工具契约 | `fibra-tool-api` | 宿主与工具插件共享 `ToolRequest`、`ToolResult`、贡献类型和可选 spill 服务 |
| 动态 contract | `fibra-fs`、`fibra-subprocess`、`fibra-shell`、`fibra-storage` | 定义一个领域的 Service 与 DTO；没有 entrypoint，不直接运行 |
| provider | `fibra-fs-local`、`fibra-subprocess-local`、`fibra-shell-local`、`fibra-storage-json` | 在一个 realm 内提供 contract 定义的 Service |
| consumer | `fibra-tool-fs`、`fibra-tool-fs-search`、`fibra-tool-shell`、`fibra-tool-storage` | 消费 Service，并把工具贡献注册到长期 `ContributionDirectory` |

provider 与 consumer 是运行时角色，不是两套插件格式。二者都是普通 Fibra 插件：provider 通过
`PluginDefinition.provide(...)` 声明服务，consumer 通过 `PluginDefinition.require(...)` 声明服务依赖。
manifest 的 `requires` 先建立制品级类型可见性，desired graph 中相同的 service realm 再决定具体实例
能否相互注入。只安装 consumer、缺少 contract/provider，或 realm 不一致时，consumer 都不会伪装成可用。

```text
宿主 classpath: fibra-tool-api

fibra-fs-local ──提供 FileSystem──> fibra-tool-fs ──注册 read/write/edit──> PublishedRuntime
fibra-subprocess-local ──提供 Subprocess──> fibra-tool-fs-search ──注册 glob/grep──> PublishedRuntime
fibra-subprocess-local ──> fibra-shell-local ──提供 Shell──> fibra-tool-shell ──注册 bash──> PublishedRuntime
fibra-storage-json ──提供 ConfigStore/event──> fibra-tool-storage ──注册 load/put/remove/changes──> PublishedRuntime
```

正式插件对动态 contract 使用同一发布列的精确版本。构建依赖必须是 `provided`，实现 JAR 不得复制
`fibra-api`、`fibra-tool-api` 或动态 contract class；实现私有的第三方库可以 relocation 后随 provider
制品发布。

`fibra-storage-json` 在 POSIX 上对临时文件和原子 rename 后的父目录分别同步。rename 已成功、但父目录
同步失败时，写入按已提交处理并记录 durability warning，内存 revision、事件与磁盘当前内容保持一致；
Windows 目录同步是 best-effort，不能据此承诺断电后的目录项持久性。

`fibra-fs-local` 把所有阻塞文件 I/O 放到受控阻塞调度器，并按稳定 target key 串行变更；无关文件可并行，
等待同一目标期间仍响应取消。写入使用同父目录的私有 staging 目录，POSIX 权限固定为目录 `0700`、临时
文件 `0600`。Windows 通过插件 JAR 私有打包、POM 标记 optional 的 JNA 在写入内容前复制受保护 DACL，
并用 `ReplaceFileW` 发布。JNA 保留原包名以匹配其固定 JNI 符号，由插件 ClassSpace 隔离而不传递给宿主；
本仓库在 macOS 上验证原生边界的调用顺序、错误映射和接线，不把它记作 Windows 实机运行证据。

## 插件模板与多模块产品

`fibra-plugin-archetype` 生成的是一个独立、可部署的插件 JAR，因此默认只有一个 Maven 工程并只依赖
`fibra-api`。这与本目录的多模块产品不矛盾：当一个产品需要稳定 contract、多个 provider 或 consumer
时，用不发布的聚合 POM 组织多个由模板规则约束的独立插件工程即可。动态 contract 需手工以 `provided`
依赖加入生成项目，并同步写入 `plugin.yaml` 的精确 `requires`。

## 验证

日常定向验证使用本地 Maven 缓存：

```bash
mvn -o -pl fibra-plugins -am verify
```

`fibra-plugins-acceptance-host` 会把正式 JAR 放到测试宿主 classpath 之外，再经 `PluginRegistry`、
`JavaPluginRuntimeAdapter` 和 `PublishedRuntime` 完成制品级装载、协作、局部更新、撤销、排空与重启验证。
