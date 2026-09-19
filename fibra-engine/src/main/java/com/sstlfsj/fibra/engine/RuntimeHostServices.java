package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.Scope;
import com.sstlfsj.fibra.ScopeView;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import reactor.core.publisher.Mono;

import java.util.Set;

public interface RuntimeHostServices {
    String hostInstanceId();

    ScopeView scope();

    /**
     * 关闭 driver 拥有的子 Scope，并在其中仍有未释放资源时报告失败。
     * 成功返回是 runtime 释放依赖该 Scope 的载荷所有权的前置条件。
     */
    Mono<Void> releaseScope(Scope scope);

    ContributionKindRegistry contributionKinds();

    ContributionAdmission openContributionAdmission(ExecutionUnitKey key);

    RemoteContributionInvoker remoteContributions();

    String nextIdentity(String namespace);

    void requestReconcile(Set<RuntimeUnitFence> fences, String reason);

    void requestObservationRefresh(RuntimeUnitFence fence);

    void requestDisable(RuntimeUnitDisableRequest request);

    void requestRecompile(RuntimeRecompileReason reason);
}
