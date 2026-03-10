package com.stripe.checkout.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Incoming checkout request from the WordPress plugin (or Postman/cURL).
 *
 * Required fields:
 *   - nameOnCard, cardNumber, expireString (MM/YY or MM/YYYY), cvc
 *   - planId: Stripe Price ID (e.g. price_1ABC...)
 *
 * Optional fields for billing:
 *   - country, state, city, address, postalCode
 *   - email, firstName, lastName (used to create Stripe customer)
 *   - eventId, description: metadata you want recorded in Stripe
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CheckoutRequest {

    // ── Card details ─────────────────────────────────────────────────────────
    private String nameOnCard;
    private String cardNumber;
    private String expireString;   // accepts "MM/YY" or "MM/YYYY" or "MMYY"
    private long   expMonth;
    private long   expYear;
    private String cvc;

    // ── Stripe plan / price ───────────────────────────────────────────────────
    private String planId;         // Stripe Price ID: price_xxxx
    private int    quantity = 1;

    // ── Customer info ─────────────────────────────────────────────────────────
    private String email;
    private String firstName;
    private String lastName;

    // ── Billing address (optional) ────────────────────────────────────────────
    private String country;
    private String state;
    private String city;
    private String address;
    private String postalCode;

    // ── Metadata (shows in Stripe dashboard) ─────────────────────────────────
    private String eventId;        // e.g. "EVT-001"
    private String description;    // e.g. "Test Event Ticket"
    private String couponCode;

    // ── Hosted Checkout (optional) ───────────────────────────────────────────
    // When using Stripe-hosted Checkout, the frontend can send these URLs.
    // successUrl may contain {CHECKOUT_SESSION_ID}, which Stripe will replace.
    private String successUrl;
    private String cancelUrl;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getNameOnCard()           { return nameOnCard; }
    public void   setNameOnCard(String v)   { this.nameOnCard = v; }

    public String getCardNumber()           { return cardNumber; }
    public void   setCardNumber(String v)   { this.cardNumber = v; }

    public String getExpireString()         { return expireString; }
    public void   setExpireString(String v) {
        this.expireString = v;
        if (v != null) {
            try {
                String[] parts;
                if (v.contains("/")) {
                    parts = v.split("/");
                } else {
                    parts = new String[]{ v.substring(0, 2), v.substring(2) };
                }
                this.expMonth = Long.parseLong(parts[0].trim());
                String yr = parts[1].trim();
                if (yr.length() == 2) yr = "20" + yr;
                this.expYear = Long.parseLong(yr);
            } catch (Exception ignored) {}
        }
    }

    public long   getExpMonth()             { return expMonth; }
    public void   setExpMonth(long v)       { this.expMonth = v; }

    public long   getExpYear()              { return expYear; }
    public void   setExpYear(long v)        { this.expYear = v; }

    public String getCvc()                  { return cvc; }
    public void   setCvc(String v)          { this.cvc = v; }

    public String getPlanId()               { return planId; }
    public void   setPlanId(String v)       { this.planId = v; }

    public int    getQuantity()             { return quantity; }
    public void   setQuantity(int v)        { this.quantity = v; }

    public String getEmail()                { return email; }
    public void   setEmail(String v)        { this.email = v; }

    public String getFirstName()            { return firstName; }
    public void   setFirstName(String v)    { this.firstName = v; }

    public String getLastName()             { return lastName; }
    public void   setLastName(String v)     { this.lastName = v; }

    public String getCountry()              { return country; }
    public void   setCountry(String v)      { this.country = v; }

    public String getState()                { return state; }
    public void   setState(String v)        { this.state = v; }

    public String getCity()                 { return city; }
    public void   setCity(String v)         { this.city = v; }

    public String getAddress()              { return address; }
    public void   setAddress(String v)      { this.address = v; }

    public String getPostalCode()           { return postalCode; }
    public void   setPostalCode(String v)   { this.postalCode = v; }

    public String getEventId()              { return eventId; }
    public void   setEventId(String v)      { this.eventId = v; }

    public String getDescription()          { return description; }
    public void   setDescription(String v)  { this.description = v; }

    public String getCouponCode()           { return couponCode; }
    public void   setCouponCode(String v)   { this.couponCode = v; }

    public String getSuccessUrl()           { return successUrl; }
    public void   setSuccessUrl(String v)   { this.successUrl = v; }

    public String getCancelUrl()            { return cancelUrl; }
    public void   setCancelUrl(String v)    { this.cancelUrl = v; }

    /** Convenience: full name from firstName + lastName, or nameOnCard */
    public String resolveFullName() {
        if (firstName != null || lastName != null) {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }
        return nameOnCard != null ? nameOnCard : "Unknown";
    }
}
