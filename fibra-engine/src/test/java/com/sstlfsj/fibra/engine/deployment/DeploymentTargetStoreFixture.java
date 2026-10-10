package com.sstlfsj.fibra.engine.deployment;

/** Same-package access for test stores; token issuance remains unavailable to consumers. */
public final class DeploymentTargetStoreFixture {
    private DeploymentTargetStoreFixture() { }

    public static DeploymentTargetStore.StoredTarget confirmed(DeploymentTarget target) {
        return DeploymentTargetStore.StoredTarget.confirmed(target);
    }
}
