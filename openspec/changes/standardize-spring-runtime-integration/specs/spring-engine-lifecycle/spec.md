## ADDED Requirements

### Requirement: Spring Lifecycle 只委托 Engine

`FibraSpringLifecycle` SHALL 只持有一个 `FibraEngine`。start 委托 engine start，stop 委托 engine close；MUST NOT 实现 loader、source、reconcile、readiness、rollback 或资源关闭顺序。

#### Scenario: Spring 启动
- **WHEN** lifecycle 收到 start
- **THEN** engine start 恰好调用一次，成功后 lifecycle 才报告 running，异常原样传播

#### Scenario: Spring 异步停止
- **WHEN** Spring调用 `stop(Runnable)`
- **THEN** engine close 恰好执行一次且 callback最终恰好调用一次，包括 close抛出异常的情况

### Requirement: 默认托管单元具有唯一 Engine 所有权

自动配置 SHALL 在宿主不存在 `FibraEngine` 和 Fibra `Context` 时创建一个 engine、一个 lifecycle、一个 bridge及只读资源视图。任一所有者已存在时 MUST 整体退让。

#### Scenario: 默认自动配置
- **WHEN** 属性合法且无用户 Engine/Context
- **THEN** 仅创建一个 engine并由 lifecycle管理，root和两个 loader的暴露 bean不拥有关闭权

#### Scenario: 宿主已有 Engine 或 Context
- **WHEN** 宿主定义任一类型
- **THEN** 默认托管单元不创建任何 engine、loader、bridge或 lifecycle

### Requirement: 宿主服务只显式桥接

`FibraServiceBridge.register(ServiceKey, service)` SHALL 把明确对象注册到 engine root。系统 MUST NOT 按 Spring bean类型自动桥接、把插件注册为 Spring bean或缓存可撤销 provider实例。

#### Scenario: 普通 Spring Bean
- **WHEN** 宿主只声明 bean而未调用 register
- **THEN** 该对象不出现在 Fibra服务 registry

