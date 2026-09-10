package com.sstlfsj.fibra.example.dependency;

@FunctionalInterface
public interface CheckoutQuoteService {
    CheckoutQuote quote(int subtotalCents);
}
