# 配置校验依赖资格

2026-10-12资格记录。Task6/7已完成正式制品、完整依赖清单及功能消费验收；用户决定本次保留Jackson3.1.5并记录七条既有告警，升级另行处理。下文保留阶段取证范围与旧ZIP身份，最终身份延续及当前验收结论见[最终交付记录](closeout.md)；不是零漏洞保证，启动性能未量化。

- 实际config reactor依赖树：networknt `json-schema-validator:3.0.8`、`com.ethlo.time:itu:1.14.0`；保留Jackson3.1.5、SLF4J2.0.18。validator传递YAML被排除，config原有直接YAML依赖仍在；未解析Joni/Graal/日志provider。日志位于`/private/tmp/fibra-config-contract-evidence/config/dependency-tree.log`。
- 两项新增依赖的精确发布POM均声明Apache License 2.0。仓库THIRD_PARTY_NOTICES已更新；JAR/POM字节摘要、大小位于`/private/tmp/fibra-config-contract-evidence/dependency-security/resolved-inputs.json`。
- 检查两个精确发布JAR，均未内嵌LICENSE/NOTICE文件。固定networknt3.0.8源码根NOTICE还包含其内置Apache Commons Validator归属，已将该NOTICE全文保留到THIRD_PARTY_NOTICES；现有根资源和发行装配本来就复制此文件及完整Apache-2.0 LICENSE，最终须检实际JAR/ZIP，而非只检POM声明。
- 另核itu 1.14.0精确sources JAR：无LICENSE/NOTICE条目，Java文件包含三种年份的Morten Haraldsen版权声明及Apache-2.0许可头，已保留归属；回执`implementation/dependency-security/itu-source-license-receipt.json`锁定源码JAR SHA及实际头部。此处仅证明发布源码JAR内容，不推断整个上游仓库无NOTICE。
- 按[OSV批量查询接口](https://google.github.io/osv.dev/post-v1-querybatch/)分别查询上述Maven坐标和精确版本，HTTP 200，两个结果均为空且无分页令牌。请求、响应与时间回执位于同目录`osv-{request,response,receipt}.json`。这只说明查询时OSV未返回这两个版本的已收录漏洞，不代表没有未知漏洞，也不替代最终完整传递依赖核验。
- [networknt官方安全公告页](https://github.com/networknt/json-schema-validator/security/advisories)在本次读取时未列已发布公告；该网页证据不作为SCA替代。itu公告页读取失败，未据此推断其安全状态。
- 宿主ClassLoader、禁止作者外部资源、精确Decimal以及编译前预算已进入config行为测试和独立审查；Task6/7已完成发行JAR/ZIP许可内容、最终完整依赖清单与实际宿主功能加载验证。启动成本未做性能测量，不能由功能启动通过推导性能保证。

## Task5 正式 ZIP 的依赖核查

- ZIP SHA256为`c3b75bad3eb15747fe95f3de0c8fbb3e684121d512f6a093ea85d16786f290e4`。清点38个宿主/插件JAR，全部宿主JAR与本轮构建仓字节一致；结合内嵌Maven元数据得到25个第三方坐标，另由shade配置及两个插件内JNA资源逐字节比较确认`net.java.dev.jna:jna:5.17.0`。该清点不是对无元数据代码的通用识别保证。
- 新增validator和itu的发行JAR合计642803字节，两个ZIP entry压缩后合计578110字节；这些数字不是整个发行物相对基线的增量，也不是启动性能指标。
- 2026-10-12T09:47:56.818960Z对上述26个坐标/版本执行OSV查询，HTTP200。新增networknt/itu未命中；既有Jackson3.1.5命中7条公告：core的`GHSA-7hhh-6rmp-j9qf`、`GHSA-p6pp-m3f8-5c89`，databind的`GHSA-cxp5-3px4-pw24`、`GHSA-gx83-3vf8-gh7j`、`GHSA-q4xh-88c3-wmh7`、`GHSA-wjgm-6hv5-3cvf`、`GHSA-wv8q-qhhj-9h54`。完整公告已保存，当时进入实际调用条件独立核对，后续结论见下文；不能宣称完整SCA无命中或已经证明当前路径可利用。未擅自改变批准的Jackson版本。
- 原始清单、请求、响应、公告和收据位于`/private/tmp/fibra-config-contract-evidence/dependency-distribution/`；最终门已核对38个JAR身份延续，据此引用该阶段证据，详见最终交付记录及Task7的`artifact-qualification.json`。
- 独立有界核查已完成，报告归档至`implementation/dependency-security/advisory-applicability.md`：新增String parser→LiteralValue→手工JsonNode路径未见直接入口，仓内模型未证明完整触发；既有Java binder允许外部配置类带入相关数值转换、注解、XML/Path类型，不能全局排除。七条在3.1支线的共同修复下界为core/databind3.1.7，尚未验证升级兼容性。2026-10-12用户明确选择“本次保留 3.1.5 并明确记录告警，升级另行处理”；本次依赖版本保持不变，升级不再作为本次待决事项。既有七条告警及条件风险仍保留，不由该选择推导零漏洞或发布授权。
- 本轮正式发布仓28个主JAR与发行ZIP根的`LICENSE`、`THIRD_PARTY_NOTICES.md`均与当前源文件原字节一致，包含新增两库说明，收据为`implementation/dependency-security/task5-published-notices.json`。56个sources/Javadoc附件未含该NOTICE也如实记账；本检查不将附件缺该文件直接判为新增缺陷，更不声称已完成所有第三方许可的法律审查。
