package com.stripe.checkout.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Salesforce integration using OAuth 2.0 Web Server flow (browser login → callback → code exchange).
 * After an admin completes {@code GET /api/salesforce/authorize}, this service holds tokens in memory
 * (replace with a secure store for production / multiple instances).
 *
 * DEMO MODE: set salesforce.demo.mode=true in application.properties
 * to return realistic fake responses without real SF credentials.
 * Switch to false when real credentials are ready.
 */
@Service
public class SalesforceService {

    private static final Logger log = LoggerFactory.getLogger(SalesforceService.class);

    private static final long DEFAULT_ACCESS_TOKEN_TTL_MS = 7_200_000L - 120_000L; // ~2h minus buffer

    @Value("${salesforce.client.id}")
    private String clientId;

    @Value("${salesforce.client.secret}")
    private String clientSecret;

    @Value("${salesforce.token.url}")
    private String tokenUrl;

    @Value("${salesforce.instance.url}")
    private String instanceUrl;

    @Value("${salesforce.api.version}")
    private String apiVersion;

    @Value("${salesforce.callback.url}")
    private String callbackUrl;

    // ── Demo mode — set true in application.properties to show fake SF responses ─
    @Value("${salesforce.demo.mode:false}")
    private boolean demoMode;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void validateConfig() {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalStateException(
                    "Missing salesforce.callback.url. Set environment variable SALESFORCE_CALLBACK_URL (or salesforce.callback.url).");
        }
        if (!demoMode) {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException(
                        "Missing Salesforce client id. Set environment variable SALESFORCE_CLIENT_ID (or salesforce.client.id).");
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalStateException(
                        "Missing Salesforce client secret. Set environment variable SALESFORCE_CLIENT_SECRET (or salesforce.client.secret).");
            }
        }
    }

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAtMillis;
    private volatile String effectiveInstanceUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private String apiBaseUrl() {
        String base = effectiveInstanceUrl;
        if (base == null || base.isBlank()) {
            base = instanceUrl;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String fakeSfId(String prefix) {
        return prefix + "DEMO" + (int) (Math.random() * 999999);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. HAS OAUTH SESSION
    // ─────────────────────────────────────────────────────────────────────────

    public boolean hasOAuthSession() {
        if (demoMode) return true;  // demo mode always appears connected
        if (accessToken != null && !isAccessTokenExpired()) {
            return true;
        }
        return refreshToken != null && !refreshToken.isBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. EXCHANGE CODE FOR TOKEN (OAuth callback)
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized Map<String, String> exchangeCodeForToken(String code) {
        if (demoMode) {
            log.info("[SF DEMO] Simulating token exchange");
            Map<String, String> result = new HashMap<>();
            result.put("access_token", "DEMO_ACCESS_TOKEN");
            result.put("instance_url", instanceUrl);
            result.put("token_type",   "Bearer");
            return result;
        }

        log.info("[SF] Exchanging authorization code for access token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "authorization_code");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri",  callbackUrl);
        params.add("code",          code);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
            Map<?, ?> body = response.getBody();
            applyTokenResponse(body);

            Map<String, String> result = new HashMap<>();
            result.put("access_token", body != null ? (String) body.get("access_token") : null);
            result.put("instance_url", body != null ? (String) body.get("instance_url") : null);
            result.put("token_type",   body != null ? (String) body.get("token_type")   : null);
            return result;

        } catch (Exception e) {
            log.error("[SF] Token exchange failed: {}", e.getMessage());
            throw new RuntimeException("Salesforce token exchange failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. GET ACCESS TOKEN
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized String getAccessToken() {
        if (demoMode) return "DEMO_TOKEN";  // demo mode skips real auth
        if (accessToken != null && !isAccessTokenExpired()) {
            return accessToken;
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshAccessToken();
            if (accessToken != null && !isAccessTokenExpired()) {
                return accessToken;
            }
        }
        throw new IllegalStateException(
                "Salesforce is not connected. Open GET /api/salesforce/authorize in a browser, "
                        + "sign in, and approve the app.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. VERIFY DATA API REACHABLE (health check)
    // ─────────────────────────────────────────────────────────────────────────

    public void verifyDataApiReachable() {
        if (demoMode) return;  // demo mode skips real API call
        String token = getAccessToken();
        String url = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. UPSERT CONTACT
    // ─────────────────────────────────────────────────────────────────────────

    public String upsertContact(String firstName, String lastName, String email) {
        log.info("[SF] Upserting contact: {} {}", firstName, email);

        if (demoMode) {
            String fakeId = fakeSfId("003");
            log.info("[SF DEMO] Contact upserted — ID: {} | Name: {} {} | Email: {}", fakeId, firstName, lastName, email);
            return fakeId;
        }

        if (!hasOAuthSession()) {
            log.info("[SF] Skipping contact upsert — OAuth not completed");
            return email;
        }

        String token = getAccessToken();
        String url   = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/Contact/Email/" + email;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> contactBody = new HashMap<>();
        contactBody.put("FirstName", firstName);
        contactBody.put("LastName",  lastName);
        contactBody.put("Email",     email);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(contactBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PATCH, request, Map.class);
            log.info("[SF] Contact upserted, status: {}", response.getStatusCode());
            Map<?, ?> body = response.getBody();
            return body != null && body.containsKey("id") ? (String) body.get("id") : email;

        } catch (Exception e) {
            log.error("[SF] Contact upsert failed: {}", e.getMessage());
            throw new RuntimeException("Salesforce contact upsert failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. CREATE OPPORTUNITY
    // ─────────────────────────────────────────────────────────────────────────

    public String createOpportunity(String contactId, String eventTitle, String amount,
                                    String stripeSessionId, boolean isMember) {
        log.info("[SF] Creating opportunity for contact: {}, event: {}", contactId, eventTitle);

        if (demoMode) {
            String fakeId = fakeSfId("006");
            log.info("[SF DEMO] Opportunity created — ID: {} | Event: {} | Amount: {} | Member: {}",
                    fakeId, eventTitle, amount, isMember);
            return fakeId;
        }

        if (!hasOAuthSession()) {
            log.info("[SF] Skipping opportunity — OAuth not completed");
            return null;
        }

        String token = getAccessToken();
        String url   = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/Opportunity/";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> oppBody = new HashMap<>();
        oppBody.put("Name",        "Event Registration - " + eventTitle);
        oppBody.put("StageName",   "Closed Won");
        oppBody.put("CloseDate",   java.time.LocalDate.now().toString());
        oppBody.put("Amount",      amount);
        oppBody.put("Description", "Stripe Session: " + stripeSessionId);
        oppBody.put("LeadSource",  "Web");
        // NOTE: update field names once field mapping doc arrives from SF admin
        // oppBody.put("Contact__c",  contactId);
        // oppBody.put("IsMember__c", isMember);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(oppBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<?, ?> body = response.getBody();
            String oppId = body != null ? (String) body.get("id") : null;
            log.info("[SF] Opportunity created: {}", oppId);
            return oppId;

        } catch (Exception e) {
            log.error("[SF] Opportunity creation failed: {}", e.getMessage());
            throw new RuntimeException("Salesforce opportunity creation failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. CHECK MEMBERSHIP
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isMember(String email) {
        log.info("[SF] Checking membership for: {}", email);

        if (demoMode) {
            // For demo: emails containing "member" = true, everything else = false
            // e.g. member@test.com → MEMBER | guest@test.com → NON-MEMBER
            boolean fakeMember = email != null && email.toLowerCase().contains("member");
            log.info("[SF DEMO] Membership check {} → {}", email, fakeMember ? "MEMBER" : "NON-MEMBER");
            return fakeMember;
        }

        if (!hasOAuthSession()) {
            log.debug("[SF] Skipping membership check — OAuth not completed");
            return false;
        }

        String token    = getAccessToken();
        String safeEmail = email.replace("'", "''");
        String soql     = "SELECT+Id,Email+FROM+Contact+WHERE+Email='" + safeEmail + "'+LIMIT+1";
        String url      = apiBaseUrl() + "/services/data/" + apiVersion + "/query?q=" + soql;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) return false;
            Integer totalSize = (Integer) body.get("totalSize");
            log.info("[SF] Membership query returned {} record(s) for {}", totalSize, email);
            return totalSize != null && totalSize > 0;

        } catch (Exception e) {
            log.warn("[SF] Membership check failed for {}: {} — defaulting to non-member", email, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. SYNC ORDER (called automatically after successful Stripe payment)
    // ─────────────────────────────────────────────────────────────────────────

    public void syncOrderToSalesforce(String firstName, String lastName, String email,
                                      String eventTitle, String amount,
                                      String stripeSessionId, boolean isMember) {
        try {
            String contactId = upsertContact(firstName, lastName, email);
            String oppId     = createOpportunity(contactId, eventTitle, amount, stripeSessionId, isMember);

            if (demoMode) {
                log.info("[SF DEMO] ✅ Full order sync complete — Contact: {} | Opportunity: {} | Member: {}",
                        contactId, oppId, isMember);
            } else {
                log.info("[SF] Order synced to Salesforce for {}", email);
            }

        } catch (Exception e) {
            // Never fail the payment because of SF — log and move on
            log.error("[SF] Order sync failed (payment still succeeded): {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void applyTokenResponse(Map<?, ?> body) {
        if (body == null || !body.containsKey("access_token")) return;

        Object at = body.get("access_token");
        if (at instanceof String) this.accessToken = (String) at;

        Object iu = body.get("instance_url");
        if (iu instanceof String) {
            String s = (String) iu;
            if (!s.isBlank())
                this.effectiveInstanceUrl = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }

        Object rt = body.get("refresh_token");
        if (rt instanceof String) {
            String rts = (String) rt;
            if (!rts.isBlank()) this.refreshToken = rts;
        }

        long expiresMs = parseExpiresInMs(body.get("expires_in"));
        this.tokenExpiresAtMillis = expiresMs > 0
                ? System.currentTimeMillis() + expiresMs - 120_000L
                : System.currentTimeMillis() + DEFAULT_ACCESS_TOKEN_TTL_MS;

        log.info("[SF] Token applied; API base: {}", apiBaseUrl());
    }

    private static long parseExpiresInMs(Object expiresIn) {
        if (expiresIn == null) return 0;
        try {
            if (expiresIn instanceof Number) return ((Number) expiresIn).longValue() * 1000L;
            return Long.parseLong(expiresIn.toString()) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isAccessTokenExpired() {
        if (accessToken == null) return true;
        if (tokenExpiresAtMillis <= 0) return false;
        return System.currentTimeMillis() >= tokenExpiresAtMillis;
    }

    private synchronized void refreshAccessToken() {
        if (refreshToken == null || refreshToken.isBlank()) return;
        log.info("[SF] Refreshing access token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type",    "refresh_token");
        params.add("client_id",     clientId);
        params.add("client_secret", clientSecret);
        params.add("refresh_token", refreshToken);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);
            applyTokenResponse(response.getBody());
        } catch (Exception e) {
            log.warn("[SF] Refresh failed: {} — re-authorize via /api/salesforce/authorize", e.getMessage());
            clearTokens();
        }
    }

    private void clearTokens() {
        this.accessToken          = null;
        this.refreshToken         = null;
        this.tokenExpiresAtMillis = 0;
        this.effectiveInstanceUrl = null;
    }
}