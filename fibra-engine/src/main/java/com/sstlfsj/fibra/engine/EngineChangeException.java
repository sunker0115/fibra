package com.sstlfsj.fibra.engine;

/** 携带失败时已经发布的实际事实；保存成功不表示运行目标已经达成。 */
public final class EngineChangeException extends RuntimeException {
    private final PublishedView view;
    private final boolean targetSaved;

    public EngineChangeException(PublishedView view, boolean targetSaved, Throwable cause) {
        super(targetSaved ? "deployment target saved but convergence failed" : "deployment target was not confirmed", cause);
        this.view = view;
        this.targetSaved = targetSaved;
    }

    public PublishedView view() { return view; }
    public boolean targetSaved() { return targetSaved; }
}
