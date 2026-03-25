package com.stripe.checkout.model;

/**
 * Response returned to the WordPress plugin after a checkout attempt.
 */
public class CheckoutResponse {

    private boolean success;
    private String  message;

    // Stripe IDs — useful for the plugin to store/display
    private String  customerId;
    private String  subscriptionId;
    private String  invoiceId;
    private String  paymentIntentId;
    private String  paymentStatus;   // "succeeded", "requires_action", etc.

    // Card summary
    private String  cardLast4;
    private String  cardBrand;

    /** Major units for Salesforce / reporting (e.g. "49.99" for USD). */
    private String  orderAmount;

    public static CheckoutResponse ok(String customerId, String subscriptionId,
                                      String invoiceId, String paymentIntentId,
                                      String paymentStatus, String cardLast4, String cardBrand,
                                      String orderAmount) {
        CheckoutResponse r = new CheckoutResponse();
        r.success         = true;
        r.message         = "Payment processed successfully";
        r.customerId      = customerId;
        r.subscriptionId  = subscriptionId;
        r.invoiceId       = invoiceId;
        r.paymentIntentId = paymentIntentId;
        r.paymentStatus   = paymentStatus;
        r.cardLast4       = cardLast4;
        r.cardBrand       = cardBrand;
        r.orderAmount     = orderAmount;
        return r;
    }

    public static CheckoutResponse error(String message) {
        CheckoutResponse r = new CheckoutResponse();
        r.success = false;
        r.message = message;
        return r;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isSuccess()          { return success; }
    public String  getMessage()         { return message; }
    public String  getCustomerId()      { return customerId; }
    public String  getSubscriptionId()  { return subscriptionId; }
    public String  getInvoiceId()       { return invoiceId; }
    public String  getPaymentIntentId() { return paymentIntentId; }
    public String  getPaymentStatus()   { return paymentStatus; }
    public String  getCardLast4()       { return cardLast4; }
    public String  getCardBrand()       { return cardBrand; }
    public String  getOrderAmount()     { return orderAmount; }
}
