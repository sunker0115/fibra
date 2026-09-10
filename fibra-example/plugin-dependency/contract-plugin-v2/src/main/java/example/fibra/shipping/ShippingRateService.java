package example.fibra.shipping;

@FunctionalInterface
public interface ShippingRateService {
    ShippingRate rateFor(int subtotalCents);
}
