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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    // ── Direct card checkout (legacy) ─────────────────────────────────────────
    public CheckoutResponse processCheckout(CheckoutRequest req) {
        try {
            BillingDetails bd = BillingDetails.builder()
                    .setName(req.resolveFullName())
                    .setEmail(req.getEmail())
                    .setAddress(BillingDetails.Address.builder()
                            .setCountry(req.getCountry()).setState(req.getState())
                            .setCity(req.getCity()).setLine1(req.getAddress())
                            .setPostalCode(req.getPostalCode()).build())
                    .build();

            PaymentMethod pm = PaymentMethod.create(
                    PaymentMethodCreateParams.builder()
                            .setType(PaymentMethodCreateParams.Type.CARD)
                            .setBillingDetails(bd)
                            .setCard(CardDetails.builder()
                                    .setNumber(req.getCardNumber())
                                    .setExpMonth(req.getExpMonth())
                                    .setExpYear(req.getExpYear())
                                    .setCvc(req.getCvc()).build())
                            .build());

            Map<String, String> meta = new HashMap<>();
            if (req.getEventId()     != null) meta.put("eventId",     req.getEventId());
            if (req.getDescription() != null) meta.put("description", req.getDescription());
            if (req.getEmail()       != null) meta.put("email",       req.getEmail());

            Customer customer = Customer.create(CustomerCreateParams.builder()
                    .setName(req.resolveFullName()).setEmail(req.getEmail())
                    .setPaymentMethod(pm.getId())
                    .setInvoiceSettings(CustomerCreateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(pm.getId()).build())
                    .putAllMetadata(meta).build());

            Price price       = Price.retrieve(req.getPlanId());
            long  totalAmount = price.getUnitAmount() * req.getQuantity();

            PaymentIntent pi = PaymentIntent.create(PaymentIntentCreateParams.builder()
                    .setAmount(totalAmount).setCurrency(price.getCurrency())
                    .setCustomer(customer.getId()).setPaymentMethod(pm.getId())
                    .setConfirm(true).setOffSession(true)
                    .setDescription(req.getDescription())
                    .putAllMetadata(meta).build());

            if (!"succeeded".equals(pi.getStatus()))
                return CheckoutResponse.error("Payment not successful. Stripe status: " + pi.getStatus());

            String cardNum = req.getCardNumber();
            String last4   = (cardNum != null && cardNum.length() >= 4) ? cardNum.substring(cardNum.length() - 4) : "****";
            String amountStr = formatStripeAmount(pi.getAmount(), pi.getCurrency());
            return CheckoutResponse.ok(customer.getId(), "one-time", pi.getId(), pi.getId(),
                    pi.getStatus(), last4, detectCardBrand(cardNum), amountStr);

        } catch (com.stripe.exception.CardException ce) {
            return CheckoutResponse.error("Card error: " + ce.getMessage());
        } catch (StripeException se) {
            return CheckoutResponse.error("Stripe error: " + se.getMessage());
        } catch (Exception ex) {
            return CheckoutResponse.error("Server error: " + ex.getMessage());
        }
    }

    // ── Single-event hosted Checkout Session ──────────────────────────────────
    public Map<String, String> createHostedCheckoutSession(CheckoutRequest req) throws StripeException {
        if (req.getPlanId() == null || req.getPlanId().isBlank())
            throw new IllegalArgumentException("planId (Stripe Price ID) is required");

        long   quantity   = req.getQuantity() > 0 ? req.getQuantity() : 1;
        String successUrl = defaultIfBlank(req.getSuccessUrl(), "https://example.com/stripe-success?session_id={CHECKOUT_SESSION_ID}");
        String cancelUrl  = defaultIfBlank(req.getCancelUrl(),  "https://example.com/stripe-cancel");

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(quantity).setPrice(req.getPlanId()).build());

        if (notBlank(req.getEmail()))       builder.setCustomerEmail(req.getEmail());
        if (notBlank(req.getEventId()))     builder.putMetadata("eventId",     req.getEventId());
        if (notBlank(req.getDescription())) builder.putMetadata("description", req.getDescription());
        if (notBlank(req.getFirstName()))   builder.putMetadata("firstName",   req.getFirstName());
        if (notBlank(req.getLastName()))    builder.putMetadata("lastName",    req.getLastName());

        // Apply Stripe coupon if the plugin sent one
        if (notBlank(req.getCouponCode())) {
            log.info("Applying Stripe coupon to single session: {}", req.getCouponCode());
            builder.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(req.getCouponCode()).build());
        }

        Session session = Session.create(builder.build());
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("url",       session.getUrl());
        return result;
    }

    // ── Multi-event cart Checkout Session ─────────────────────────────────────
    @SuppressWarnings("unchecked")
    public Map<String, String> createCartCheckoutSession(Map<String, Object> req) throws StripeException {
        List<Map<String, Object>> lineItems = (List<Map<String, Object>>) req.get("lineItems");
        if (lineItems == null || lineItems.isEmpty())
            throw new IllegalArgumentException("lineItems array is required and must not be empty");

        String email      = strVal(req, "email");
        String firstName  = strVal(req, "firstName");
        String lastName   = strVal(req, "lastName");
        String couponId   = strVal(req, "couponId");
        String description= strVal(req, "description");
        String successUrl = defaultIfBlank(strVal(req, "successUrl"), "https://example.com/stripe-success?session_id={CHECKOUT_SESSION_ID}");
        String cancelUrl  = defaultIfBlank(strVal(req, "cancelUrl"),  "https://example.com/stripe-cancel");
        boolean isMember  = Boolean.TRUE.equals(req.get("isMember"));

        log.info("Cart session | items={} | email={} | member={} | coupon={}", lineItems.size(), email, isMember, couponId);

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl);

        for (Map<String, Object> item : lineItems) {
            String priceId = strVal(item, "price_id");
            if (priceId == null || priceId.isBlank()) continue;
            long qty = 1L;
            Object qtyObj = item.get("quantity");
            if (qtyObj instanceof Number) qty = ((Number) qtyObj).longValue();
            if (qty < 1) qty = 1;
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                    .setPrice(priceId).setQuantity(qty).build());
        }

        if (notBlank(email)) builder.setCustomerEmail(email);
        if (notBlank(firstName + lastName))
            builder.putMetadata("customerName", (firstName + " " + lastName).trim());
        if (notBlank(firstName)) builder.putMetadata("firstName", firstName);
        if (notBlank(lastName))  builder.putMetadata("lastName", lastName);
        if (notBlank(description)) builder.putMetadata("description", description);
        builder.putMetadata("isMember",  String.valueOf(isMember));
        builder.putMetadata("itemCount", String.valueOf(lineItems.size()));

        if (notBlank(couponId)) {
            log.info("Applying Stripe coupon to cart session: {}", couponId);
            builder.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(couponId).build());
        }

        Session session = Session.create(builder.build());
        log.info("Cart session created: {}", session.getId());

        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("url",       session.getUrl());
        return result;
    }

    // ── Retrieve session payment intent ───────────────────────────────────────
    public Map<String, String> getSessionPaymentIntent(String sessionId) throws StripeException {
        Session session = Session.retrieve(sessionId);
        Map<String, String> result = new HashMap<>();
        result.put("sessionId",       session.getId());
        result.put("status",          session.getStatus());
        result.put("paymentIntentId", session.getPaymentIntent() != null ? session.getPaymentIntent() : "");
        result.put("customerEmail",   session.getCustomerEmail() != null ? session.getCustomerEmail() : "");

        Long amountTotal = session.getAmountTotal();
        if (amountTotal != null && session.getCurrency() != null) {
            result.put("amountTotal", formatStripeAmount(amountTotal, session.getCurrency()));
        } else {
            result.put("amountTotal", "0");
        }

        Map<String, String> meta = session.getMetadata();
        if (meta != null) {
            putIfPresent(result, "firstName",       meta.get("firstName"));
            putIfPresent(result, "lastName",        meta.get("lastName"));
            putIfPresent(result, "eventDescription", meta.get("description"));
            putIfPresent(result, "isMember",       meta.get("isMember"));
            putIfPresent(result, "eventId",         meta.get("eventId"));
        }
        return result;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private String detectCardBrand(String n) {
        if (n == null) return "unknown";
        n = n.replaceAll("\\s", "");
        if (n.startsWith("4")) return "Visa";
        if (n.startsWith("5")) return "Mastercard";
        if (n.startsWith("3")) return "Amex";
        if (n.startsWith("6")) return "Discover";
        return "unknown";
    }
    private boolean notBlank(String s)                      { return s != null && !s.isBlank(); }
    private String  defaultIfBlank(String val, String fb)   { return notBlank(val) ? val : fb; }
    private String  strVal(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof String ? (String) v : ""; }

    private static void putIfPresent(Map<String, String> m, String key, String val) {
        if (val != null && !val.isEmpty()) m.put(key, val);
    }

    /** Stripe amounts are in the smallest currency unit (e.g. cents); JPY has zero decimal places. */
    private static String formatStripeAmount(long amountSmallest, String currency) {
        String c = currency != null ? currency.toLowerCase() : "usd";
        if ("jpy".equals(c) || "vnd".equals(c) || "krw".equals(c)) {
            return String.valueOf(amountSmallest);
        }
        return BigDecimal.valueOf(amountSmallest).movePointLeft(2).toPlainString();
    }
}