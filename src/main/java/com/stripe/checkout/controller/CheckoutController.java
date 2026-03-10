package com.stripe.checkout.controller;

import com.stripe.checkout.config.StripeConfig;
import com.stripe.checkout.model.CheckoutRequest;
import com.stripe.checkout.model.CheckoutResponse;
import com.stripe.checkout.service.StripeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Stripe Checkout REST Controller.
 *
 * All endpoints are PUBLIC — no authentication required.
 * This is intentional for the WordPress plugin demo.
 *
 * Endpoints:
 *   GET  /api/health          → confirm API is running
 *   GET  /api/config          → returns your publishable key for front-end use
 *   POST /api/checkout        → main checkout: create customer + subscription in Stripe
 */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    @Autowired
    private StripeService stripeService;

    @Autowired
    private StripeConfig stripeConfig;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/health
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Health check — use this to confirm ngrok → Spring Boot is working.
     *
     * cURL: curl https://YOUR-NGROK-URL/api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Stripe Checkout API is running");
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/config
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the Stripe publishable key for use in front-end / WordPress plugin.
     *
     * cURL: curl https://YOUR-NGROK-URL/api/config
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> config() {
        Map<String, String> response = new HashMap<>();
        response.put("publishableKey", stripeConfig.getPublishableKey());
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/checkout
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main checkout endpoint.
     *
     * Accepts JSON body. Creates a Stripe Customer + Subscription.
     * Verifies the PaymentIntent succeeded and returns the result.
     *
     * Example cURL:
     *
     *   curl -X POST https://YOUR-NGROK-URL/api/checkout \
     *     -H "Content-Type: application/json" \
     *     -d '{
     *           "firstName": "John",
     *           "lastName": "Doe",
     *           "email": "john@example.com",
     *           "cardNumber": "4242424242424242",
     *           "expireString": "12/34",
     *           "cvc": "123",
     *           "planId": "price_YOUR_PRICE_ID",
     *           "country": "US",
     *           "postalCode": "10001",
     *           "eventId": "EVT-001",
     *           "description": "Test Event Ticket"
     *         }'
     */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody CheckoutRequest request) {
        log.info("Checkout request received for plan: {} | event: {}",
                request.getPlanId(), request.getEventId());

        // Basic validation
        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(CheckoutResponse.error("planId is required (Stripe Price ID)"));
        }
        if (request.getCardNumber() == null || request.getCvc() == null) {
            return ResponseEntity.badRequest()
                    .body(CheckoutResponse.error("cardNumber and cvc are required"));
        }
        if (request.getExpMonth() == 0 && request.getExpireString() == null) {
            return ResponseEntity.badRequest()
                    .body(CheckoutResponse.error("Card expiry is required (expireString or expMonth+expYear)"));
        }

        CheckoutResponse result = stripeService.processCheckout(request);

        if (result.isSuccess()) {
            log.info("Checkout SUCCESS — customer: {} | subscription: {}",
                    result.getCustomerId(), result.getSubscriptionId());
            return ResponseEntity.ok(result);
        } else {
            log.warn("Checkout FAILED: {}", result.getMessage());
            return ResponseEntity.status(402).body(result);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/checkout/session  (Stripe-hosted Checkout)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Stripe-hosted Checkout Session.
     *
     * The WordPress plugin should:
     *   1) POST JSON here (planId, quantity, email, optional successUrl/cancelUrl)
     *   2) Receive { "sessionId": "...", "url": "https://checkout.stripe.com/..." }
     *   3) Open the "url" in a new window/tab for the user to complete payment.
     */
    @PostMapping("/checkout/session")
    public ResponseEntity<Map<String, String>> createCheckoutSession(@RequestBody CheckoutRequest request) {
        log.info("Hosted Checkout Session request for plan: {}", request.getPlanId());

        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "planId is required (Stripe Price ID)");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, String> session = stripeService.createHostedCheckoutSession(request);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException ex) {
            Map<String, String> error = new HashMap<>();
            error.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Failed to create Checkout Session", ex);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to create Checkout Session: " + ex.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
