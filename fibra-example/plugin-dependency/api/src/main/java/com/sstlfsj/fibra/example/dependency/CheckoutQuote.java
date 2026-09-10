package com.sstlfsj.fibra.example.dependency;

public record CheckoutQuote(int subtotalCents, int shippingCents,
                            String policyVersion) {
    public CheckoutQuote {
        if (subtotalCents < 0) {
            throw new IllegalArgumentException("subtotalCents must not be negative");
        }
        if (shippingCents < 0) {
            throw new IllegalArgumentException("shippingCents must not be negative");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
    }

    public int totalCents() {
        return Math.addExact(subtotalCents, shippingCents);
    }
}
