package fixture.hostapi;

import com.sstlfsj.fibra.bridge.ContributionKind;

/** 中立业务合同；测试只导出列明的类型，不导出同包 HostBusiness。 */
public final class DocumentApi {
    public static final ContributionKind<Descriptor, Request, Result> KIND =
        ContributionKind.local("fixture.document", Descriptor.class, Request.class, Result.class);

    private DocumentApi() { }

    public record Descriptor(String name) { }
    public record Request(String text) { }
    public record Result(String text) { }
}
