package fixture;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionRegistration;

import java.util.function.Consumer;

public final class ContributionObserver {
    public static volatile Consumer<Published> callback;
    public static volatile Consumer<Context> start;

    private ContributionObserver() { }

    public record Published(ContributionKind<String, String, String> kind,
                            ContributionRegistration registration) { }
}
