package example.fibra.shipping;

import com.sstlfsj.fibra.ServiceKey;

public final class ShippingRateServices {
    public static final ServiceKey<ShippingRateService> SHIPPING_RATE =
        new ServiceKey<>("example.shipping-rate", ShippingRateService.class);

    private ShippingRateServices() {
    }
}
