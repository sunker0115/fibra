package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.artifact.ExecutionTarget;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Engine 长期独占的 execution 编译、收敛和 observed 所有者。 */
public interface ExecutionRuntime {
    ExecutionTarget id();
    /** 纯计算，不启动进程、不访问网络，也不改变 runtime 状态。 */
    ExecutionTargetPlan compile(DeploymentTarget target,
                                Map<ArtifactId, PreparedArtifact> preparedArtifacts);
    /** 只创建可登记句柄，不执行 I/O。 */
    ExecutionUpdate createUpdate(ExecutionTargetPlan plan);
    /** 订阅时先发布当前不可变快照，随后发布最新 observed。 */
    Flux<List<ExecutionObservation>> observations();
    Mono<Void> closeAsync();
}
