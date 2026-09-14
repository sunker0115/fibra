package com.sstlfsj.fibra.engine;

/** 携带失败时已经发布的实际事实；保存成功不表示运行目标已经达成。 */
public final class EngineChangeException extends RuntimeException {
    private final PublishedView view;
    private final TargetSaveState targetSaveState;

    public EngineChangeException(PublishedView view, TargetSaveState targetSaveState,
                                 Throwable cause) {
        super(message(targetSaveState), cause);
        this.view = view;
        this.targetSaveState = java.util.Objects.requireNonNull(
            targetSaveState, "targetSaveState");
    }

    public PublishedView view() { return view; }
    public TargetSaveState targetSaveState() { return targetSaveState; }

    private static String message(TargetSaveState state) {
        return switch (java.util.Objects.requireNonNull(state, "targetSaveState")) {
            case SAVED -> "deployment target saved but convergence failed";
            case UNCONFIRMED -> "deployment target save was not confirmed";
            case NOT_SAVED -> "deployment target was not saved";
            case NOT_APPLICABLE -> "operation did not save the deployment target";
        };
    }
}
