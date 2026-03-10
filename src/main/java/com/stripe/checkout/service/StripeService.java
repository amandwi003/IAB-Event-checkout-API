package com.stripe.checkout.service;

import com.stripe.checkout.model.CheckoutRequest;
import com.stripe.checkout.model.CheckoutResponse;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodCreateParams;
import com.stripe.param.PaymentMethodCreateParams.BillingDetails;
import com.stripe.param.PaymentMethodCreateParams.CardDetails;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    public CheckoutResponse processCheckout(CheckoutRequest req) {
        log.info("Processing checkout for: {} | planId: {}", req.resolveFullName(), req.getPlanId());

        try {
            // ── 1. Billing details ────────────────────────────────────────────
            BillingDetails billingDetails = BillingDetails.builder()
                    .setName(req.resolveFullName())
                    .setEmail(req.getEmail())
                    .setAddress(BillingDetails.Address.builder()
                            .setCountry(req.getCountry())
                            .setState(req.getState())
                            .setCity(req.getCity())
                            .setLine1(req.getAddress())
                            .setPostalCode(req.getPostalCode())
                            .build())
                    .build();

            // ── 2. Create PaymentMethod from card details ─────────────────────
            PaymentMethod paymentMethod = PaymentMethod.create(
                    PaymentMethodCreateParams.builder()
                            .setType(PaymentMethodCreateParams.Type.CARD)
                            .setBillingDetails(billingDetails)
                            .setCard(CardDetails.builder()
                                    .setNumber(req.getCardNumber())
                                    .setExpMonth(req.getExpMonth())
                                    .setExpYear(req.getExpYear())
                                    .setCvc(req.getCvc())
                                    .build())
                            .build());
            log.debug("PaymentMethod created: {}", paymentMethod.getId());

            // ── 3. Metadata ───────────────────────────────────────────────────
            Map<String, String> metadata = new HashMap<>();
            if (req.getEventId()     != null) metadata.put("eventId",     req.getEventId());
            if (req.getDescription() != null) metadata.put("description", req.getDescription());
            if (req.getEmail()       != null) metadata.put("email",       req.getEmail());
            if (req.getPlanId()      != null) metadata.put("planId",      req.getPlanId());

            // ── 4. Create Customer ────────────────────────────────────────────
            Customer customer = Customer.create(
                    CustomerCreateParams.builder()
                            .setName(req.resolveFullName())
                            .setEmail(req.getEmail())
                            .setPaymentMethod(paymentMethod.getId())
                            .setInvoiceSettings(
                                    CustomerCreateParams.InvoiceSettings.builder()
                                            .setDefaultPaymentMethod(paymentMethod.getId())
                                            .build())
                            .putAllMetadata(metadata)
                            .build());
            log.debug("Customer created: {}", customer.getId());

            // ── 5. Fetch price amount from Stripe ─────────────────────────────
            Price price = Price.retrieve(req.getPlanId());
            long totalAmount = price.getUnitAmount() * req.getQuantity();
            log.debug("Amount: {} {} x{}", totalAmount, price.getCurrency(), req.getQuantity());

            // ── 6. Create & confirm one-off PaymentIntent ─────────────────────
            PaymentIntent paymentIntent = PaymentIntent.create(
                    PaymentIntentCreateParams.builder()
                            .setAmount(totalAmount)
                            .setCurrency(price.getCurrency())
                            .setCustomer(customer.getId())
                            .setPaymentMethod(paymentMethod.getId())
                            .setConfirm(true)
                            .setOffSession(true)
                            .setDescription(req.getDescription())
                            .putAllMetadata(metadata)
                            .build());

            String status = paymentIntent.getStatus();
            log.info("PaymentIntent {} status: {}", paymentIntent.getId(), status);

            if (!"succeeded".equals(status)) {
                return CheckoutResponse.error("Payment not successful. Stripe status: " + status);
            }

            // ── 7. Return success ─────────────────────────────────────────────
            String cardNum = req.getCardNumber();
            String last4   = (cardNum != null && cardNum.length() >= 4)
                    ? cardNum.substring(cardNum.length() - 4) : "****";

            return CheckoutResponse.ok(
                    customer.getId(),
                    "one-time",
                    paymentIntent.getId(),
                    paymentIntent.getId(),
                    status,
                    last4,
                    detectCardBrand(cardNum)
            );

        } catch (com.stripe.exception.CardException ce) {
            log.warn("Card error: {}", ce.getMessage());
            return CheckoutResponse.error("Card error: " + ce.getMessage());
        } catch (com.stripe.exception.InvalidRequestException ire) {
            log.warn("Invalid Stripe request: {}", ire.getMessage());
            return CheckoutResponse.error("Invalid request: " + ire.getMessage());
        } catch (StripeException se) {
            log.error("Stripe exception: {}", se.getMessage());
            return CheckoutResponse.error("Stripe error: " + se.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error: {}", ex.getMessage(), ex);
            return CheckoutResponse.error("Server error: " + ex.getMessage());
        }
    }

    /**
     * Creates a Stripe-hosted Checkout Session (no raw card data on our side).
     *
     * The frontend should open the returned URL in a new window/tab.
     */
    public Map<String, String> createHostedCheckoutSession(CheckoutRequest req) throws StripeException {
        // Basic required fields
        if (req.getPlanId() == null || req.getPlanId().isBlank()) {
            throw new IllegalArgumentException("planId (Stripe Price ID) is required");
        }

        long quantity = req.getQuantity() > 0 ? req.getQuantity() : 1;

        // Success / cancel URLs — frontend can pass them, or you can hardcode
        String successUrl = req.getSuccessUrl();
        String cancelUrl  = req.getCancelUrl();

        if (successUrl == null || successUrl.isBlank()) {
            // You can change this to your real WP success URL
            successUrl = "https://example.com/stripe-success?session_id={CHECKOUT_SESSION_ID}";
        }
        if (cancelUrl == null || cancelUrl.isBlank()) {
            // You can change this to your real WP cancel URL
            cancelUrl = "https://example.com/stripe-cancel";
        }

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(quantity)
                                .setPrice(req.getPlanId())
                                .build()
                );

        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            builder.setCustomerEmail(req.getEmail());
        }

        // Optional metadata
        if (req.getEventId() != null) {
            builder.putMetadata("eventId", req.getEventId());
        }
        if (req.getDescription() != null) {
            builder.putMetadata("description", req.getDescription());
        }

        Session session = Session.create(builder.build());

        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("url", session.getUrl());
        return result;
    }

    private String detectCardBrand(String cardNumber) {
        if (cardNumber == null) return "unknown";
        String num = cardNumber.replaceAll("\\s", "");
        if (num.startsWith("4")) return "Visa";
        if (num.startsWith("5")) return "Mastercard";
        if (num.startsWith("3")) return "Amex";
        if (num.startsWith("6")) return "Discover";
        return "unknown";
    }
}