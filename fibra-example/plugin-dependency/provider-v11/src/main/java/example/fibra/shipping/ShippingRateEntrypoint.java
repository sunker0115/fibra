package example.fibra.shipping;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import reactor.core.publisher.Mono;

public final class ShippingRateEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("shipping-rate-provider", Void.class,
                () -> (context, config) -> {
                    context.services().provide(
                        ShippingRateServices.SHIPPING_RATE,
                        subtotalCents -> new ShippingRate(
                            subtotalCents >= 5_000 ? 0 : 800, "1.1"));
                    return Mono.empty();
                })
            .provide(ShippingRateServices.SHIPPING_RATE)
            .build();
    }
}
