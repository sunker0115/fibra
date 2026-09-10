package example.fibra.checkout;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PluginEntrypoint;
import com.sstlfsj.fibra.example.dependency.CheckoutQuote;
import com.sstlfsj.fibra.example.dependency.CheckoutQuoteServices;
import example.fibra.shipping.ShippingRateServices;
import reactor.core.publisher.Mono;

public final class CheckoutQuoteEntrypoint implements PluginEntrypoint<Void> {
    @Override
    public PluginDefinition<Void> definition() {
        return PluginDefinition.builder("checkout-quote-consumer", Void.class,
                () -> (context, config) -> {
                    var shippingRates = context.services().reference(
                        ShippingRateServices.SHIPPING_RATE);
                    context.services().provide(CheckoutQuoteServices.CHECKOUT_QUOTE,
                        subtotalCents -> shippingRates.invoke((invocation, service) -> {
                            var rate = service.rateFor(subtotalCents);
                            return new CheckoutQuote(subtotalCents,
                                rate.shippingCents(), rate.policyVersion());
                        }));
                    return Mono.empty();
                })
            .require(ShippingRateServices.SHIPPING_RATE)
            .provide(CheckoutQuoteServices.CHECKOUT_QUOTE)
            .build();
    }
}
