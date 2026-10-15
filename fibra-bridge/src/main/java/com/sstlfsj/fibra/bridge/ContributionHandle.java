package com.sstlfsj.fibra.bridge;

import java.util.Objects;

/**
 * 从单个 PublishedView 即时捕获的进程内调用凭据，绑定该 view 的 viewRevision 与当时的
 * registrationIdentity。两个 fence 都是进程内事实：进程重启或 contribution 重注册后即失效。
 * 必须按 view 即时捕获（见 PublishedView.resolve），严禁跨 view 缓存或持久化后复用；恢复场景应对
 * 当前 view 重新解析，而不是把旧 handle 当作持久键。
 */
public record ContributionHandle(String viewRevision, long registrationIdentity,
                                 ContributionId id) {
    public ContributionHandle {
        viewRevision = Objects.requireNonNull(viewRevision, "viewRevision");
        if (viewRevision.isBlank()) {
            throw new IllegalArgumentException("viewRevision must not be blank");
        }
        if (registrationIdentity < 1) {
            throw new IllegalArgumentException("registrationIdentity must be positive");
        }
        Objects.requireNonNull(id, "id");
    }
}
