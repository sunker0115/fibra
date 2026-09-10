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
                        subtotalCents -> new ShippingRate(1_200, "1.0"));
                    return Mono.empty();
                })
            .provide(ShippingRateServices.SHIPPING_RATE)
            .build();
    }
}
