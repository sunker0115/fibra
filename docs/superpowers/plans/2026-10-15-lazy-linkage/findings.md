# 事实与证据

根因链：factory首次链接缺失Host API -> NoClassDefFoundError -> LifecycleDispatcher.invoke重抛Error ->
Reactor3.8.6 throwIfFatal把LinkageError抛到scheduler，未给等待者onError；外层Java runtime仅catch RuntimeException无法恢复一个未返回的mount。
core的dispatcher/defaultplugins/instance代码在e133814至79626c0未改，JavaRuntimeDriver仅类可见性变更。

直接授权证据：Tela会话01a137d3-7b3c-7a61-b8dd-ede87277ea37，turn01a13ce2-0343-7271-a6df-45fda989cb7f的userMessage，
用户选择“采用，并允许推进Fibra通用共享API”，并要求“需要fibra的改动…单独在fibra中创建会话…前期验证可以在这个会话”。
本次只读Claude不是取消原交付范围。助手转述本身不当作授权。

Tela证据根/private/tmp/tela-p3-core-implementation-e133814：
model-host-api-invalid-packages-isolated为6/F1/E0/S0/native1；model-host-api-current-host-regression为394/F1/E0/S0/native1。
独审报告SHA18e3a34b28312fa25d864bbcd56a8e1081848a4e893778b76311149555a8a7a2，产品B1/I1；隔离测试B0/I0/M0。
阶段候选SHA0e09f18a725d8c2af89046e7f3a7cd2091adc576afe96758c8bba3de189a02e9，仅研究输入，不是已采纳权威。
缺失已声明合同与facet漏声明隐含依赖必须区分，不承诺所有隐含依赖保存前LOAD拒绝；保存态按实际阶段。

原主树有用户新建.claude及正在工作的api-convergence工作树，均不动。
