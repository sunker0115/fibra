package com.sstlfsj.fibra.bridge;

/** contribution 因调用侧资源清理失败而未能完成排空。 */
public final class ContributionDrainException extends IllegalStateException {
    private final String detail;

    ContributionDrainException(ContributionId id, String detail) {
        super("contribution cleanup failed: " + id.providerInstanceId()
            + '/' + id.localName() + ": " + detail);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
