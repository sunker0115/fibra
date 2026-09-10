package com.sstlfsj.fibra.example.dependency;

import com.sstlfsj.fibra.ServiceKey;

public final class CheckoutQuoteServices {
    public static final ServiceKey<CheckoutQuoteService> CHECKOUT_QUOTE =
        new ServiceKey<>("example.checkout-quote", CheckoutQuoteService.class);

    private CheckoutQuoteServices() {
    }
}
