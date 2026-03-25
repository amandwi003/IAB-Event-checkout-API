package com.stripe.checkout.config;

import com.stripe.Stripe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Initializes the Stripe SDK with your secret key on startup.
 * All keys come from application.properties — nothing is hardcoded.
 */
@Configuration
public class StripeConfig {

    @Value("${stripe.secret.key}")
    private String secretKey;

    @Value("${stripe.publishable.key}")
    private String publishableKey;

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "Missing Stripe secret key. Set environment variable STRIPE_SECRET_KEY (or stripe.secret.key).");
        }
        Stripe.apiKey = secretKey;
        int start = Math.max(0, secretKey.length() - 6);
        String maskedTail = secretKey.substring(start);
        System.out.println("[StripeConfig] Stripe initialized with key ending in: ..." + maskedTail);
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    public String getSecretKey() {
        return secretKey;
    }
}
