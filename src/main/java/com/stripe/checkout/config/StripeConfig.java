package com.stripe.checkout.config;

import com.stripe.Stripe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

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
        Stripe.apiKey = secretKey;
        System.out.println("[StripeConfig] Stripe initialized with key ending in: ..."
                + secretKey.substring(Math.max(0, secretKey.length() - 6)));
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    public String getSecretKey() {
        return secretKey;
    }
}
