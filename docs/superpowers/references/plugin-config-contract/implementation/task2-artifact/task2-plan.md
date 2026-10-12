Task2: artifact 固定配置资源
1. 已核源码/批准 §6/resource-protocol-review；集中 Store.record 注入，原 digest 同块派生。
2. API bootstrap RED nativeExit=1: NoSuchMethod(configurationResource), 1 test。最小 API 建立后以已核 record 成功读取作为行为 RED。
3. 实现派生证据、record最终身份绑定和同步 resource 读/关状态机。
4. artifact -am 全测、自审 diff、记录源 hash 与最终验证。仅 artifact，禁止 commit/push，主线程独审前停止。
停止条件：公共合同歧义或同因两次失败，向主线程报告。

完成：最终88项全绿，已保留RED/GREEN/commands/source-sha256/patch/task2-result，自审完成；停止编辑与Maven，等待主线程独审。
