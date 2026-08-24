## 1. Archetype 制品

- [x] 1.1 新增可发布 `fibra-plugin-archetype` 和统一 Maven Archetype Plugin 3.4.1 版本
- [x] 1.2 定义 required properties、文件集和 contract/plugin 多模块生成结构
- [x] 1.3 生成标准 plugin ZIP、示例 config 和 deployment ZIP

## 2. 自动验收

- [x] 2.1 使用 archetype integration-test 生成独立项目并执行 `verify`
- [x] 2.2 校验生成 POM不继承 Fibra parent、不引用 reactor 或未解析版本
- [x] 2.3 用隔离本地仓库生成、构建并由 `FibraEngine` 装载产物

## 3. 发布与文档

- [x] 3.1 把发布门禁和可复现构建扩展为十个可发布制品
- [x] 3.2 写入 README、插件作者指南、IDEA和命令行生成方式
- [x] 3.3 完成模板可用性审查、全量验证并归档 change

精确实现计划为 `docs/superpowers/plans/2026-08-24-fibra-plugin-archetype.md`；计划通过人工闸门前不得修改生产代码。
