package com.stripe.checkout.controller;

import com.stripe.checkout.config.StripeConfig;
import com.stripe.checkout.model.CheckoutRequest;
import com.stripe.checkout.model.CheckoutResponse;
import com.stripe.checkout.service.SalesforceService;
import com.stripe.checkout.service.StripeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stripe Checkout REST Controller.
 *
 * Endpoints:
 *   GET  /api/health                → confirm API is running
 *   GET  /api/config                → return Stripe publishable key
 *   POST /api/checkout              → direct card checkout (legacy)
 *   POST /api/checkout/session      → hosted Stripe Checkout session (single event)
 *   POST /api/checkout/cart-session → hosted Stripe Checkout session (multi-event cart)
 *   GET  /api/checkout/session/{id} → retrieve session + payment intent after payment
 */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    @Autowired
    private StripeService stripeService;

    @Autowired
    private StripeConfig stripeConfig;

    @Autowired
    private SalesforceService salesforceService;

    // GET /api/health
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Stripe Checkout API is running");
        return ResponseEntity.ok(response);
    }

    // GET /api/config
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> config() {
        Map<String, String> response = new HashMap<>();
        response.put("publishableKey", stripeConfig.getPublishableKey());
        return ResponseEntity.ok(response);
    }

    // POST /api/checkout  (direct card — legacy)
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody CheckoutRequest request) {
        log.info("Checkout request for plan: {} | event: {}", request.getPlanId(), request.getEventId());

        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            return ResponseEntity.badRequest().body(CheckoutResponse.error("planId is required"));
        }
        if (request.getCardNumber() == null || request.getCvc() == null) {
            return ResponseEntity.badRequest().body(CheckoutResponse.error("cardNumber and cvc are required"));
        }
        if (request.getExpMonth() == 0 && request.getExpireString() == null) {
            return ResponseEntity.badRequest().body(CheckoutResponse.error("Card expiry is required"));
        }

        CheckoutResponse result = stripeService.processCheckout(request);

        if (result.isSuccess()) {
            log.info("Checkout SUCCESS — customer: {}", result.getCustomerId());
            String email = request.getEmail();
            boolean isMember = email != null && !email.isBlank() && salesforceService.isMember(email);
            String amount = result.getOrderAmount() != null ? result.getOrderAmount() : "0";
            String eventTitle = request.getDescription() != null ? request.getDescription() : "Event Ticket";
            salesforceService.syncOrderToSalesforce(
                    nullToEmpty(request.getFirstName()),
                    nullToEmpty(request.getLastName()),
                    nullToEmpty(email),
                    eventTitle,
                    amount,
                    result.getPaymentIntentId(),
                    isMember);
            return ResponseEntity.ok(result);
        } else {
            log.warn("Checkout FAILED: {}", result.getMessage());
            return ResponseEntity.status(402).body(result);
        }
    }

    // POST /api/checkout/session  (Stripe-hosted — single event)
    @PostMapping("/checkout/session")
    public ResponseEntity<Map<String, String>> createCheckoutSession(@RequestBody CheckoutRequest request) {
        log.info("Hosted Checkout Session for plan: {}", request.getPlanId());

        if (request.getPlanId() == null || request.getPlanId().isBlank()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "planId is required");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, String> session = stripeService.createHostedCheckoutSession(request);

            if (request.getEmail() != null && !request.getEmail().isBlank()) {
                try {
                    salesforceService.upsertContact(
                            nullToEmpty(request.getFirstName()),
                            nullToEmpty(request.getLastName()),
                            request.getEmail());
                } catch (Exception sfEx) {
                    log.warn("Salesforce contact upsert failed for {}. Continuing checkout session creation: {}",
                            request.getEmail(), sfEx.getMessage());
                }
            }

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

    // POST /api/checkout/cart-session  (Stripe-hosted — multi-event cart)
    @PostMapping("/checkout/cart-session")
    public ResponseEntity<Map<String, String>> createCartCheckoutSession(@RequestBody Map<String, Object> request) {
        log.info("Cart Checkout Session request");

        Object lineItemsObj = request.get("lineItems");
        if (!(lineItemsObj instanceof List) || ((List<?>) lineItemsObj).isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "lineItems array is required and must not be empty");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, String> session = stripeService.createCartCheckoutSession(request);

            String email = strOrEmpty(request.get("email"));
            String firstName = strOrEmpty(request.get("firstName"));
            String lastName = strOrEmpty(request.get("lastName"));

            if (!email.isBlank()) {
                try {
                    salesforceService.upsertContact(firstName, lastName, email);
                } catch (Exception sfEx) {
                    log.warn("Salesforce contact upsert failed for {}. Continuing cart session creation: {}",
                            email, sfEx.getMessage());
                }
            }

            return ResponseEntity.ok(session);

        } catch (IllegalArgumentException ex) {
            Map<String, String> error = new HashMap<>();
            error.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception ex) {
            log.error("Failed to create Cart Session", ex);
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to create Cart Session: " + ex.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // GET /api/checkout/session/{sessionId}
    // When payment is complete, run full Salesforce order sync (contact was upserted at session start).
    @GetMapping("/checkout/session/{sessionId}")
    public ResponseEntity<Map<String, String>> getCheckoutSession(@PathVariable String sessionId) {
        log.info("Retrieving session: {}", sessionId);
        try {
            Map<String, String> result = stripeService.getSessionPaymentIntent(sessionId);

            String status = result.get("status");
            if ("complete".equals(status)) {
                String email = result.get("customerEmail");
                if (email != null && !email.isBlank()) {
                    String firstName = result.getOrDefault("firstName", "");
                    String lastName = result.getOrDefault("lastName", "");
                    String eventTitle = result.getOrDefault("eventDescription", "Event Registration");
                    String amount = result.getOrDefault("amountTotal", "0");
                    String metaMember = result.get("isMember");
                    boolean isMember;
                    if (metaMember != null) {
                        isMember = "true".equalsIgnoreCase(metaMember);
                    } else {
                        isMember = !email.isBlank() && salesforceService.isMember(email);
                    }

                    salesforceService.syncOrderToSalesforce(
                            firstName,
                            lastName,
                            email,
                            eventTitle,
                            amount,
                            sessionId,
                            isMember);
                }
            }

            return ResponseEntity.ok(result);

        } catch (Exception ex) {
            log.error("Failed to retrieve session {}: {}", sessionId, ex.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Could not retrieve session: " + ex.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String strOrEmpty(Object o) {
        return o instanceof String ? (String) o : "";
    }
}
