package fixture;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import reactor.core.publisher.Mono;

public final class ContributionEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("sample", Void.class, () ->
            (context, ignored) -> {
                var start = ContributionObserver.start;
                if (start != null) {
                    start.accept(context);
                }
                var kind = ContributionKind.local("fixture.echo", String.class,
                    String.class, String.class);
                var registrar = context.services().require(
                    ContributionServices.REGISTRAR);
                return registrar.register(context, kind, "echo",
                    "fixture", (invocation, input) -> Mono.just("echo:" + input))
                    .doOnNext(registration -> {
                        var callback = ContributionObserver.callback;
                        if (callback != null) {
                            callback.accept(new ContributionObserver.Published(kind,
                                registration));
                        }
                    }).then();
            }).build();
    }
}
