package com.stripe.checkout.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    @Value("${salesforce.contact.object:Contact}")
    private String contactObjectApiName;

    @Value("${salesforce.contact.external.id.field:Email}")
    private String contactExternalIdField;

    @Value("${salesforce.order.object:Opportunity}")
    private String orderObjectApiName;

    @Value("${salesforce.order.name.field:Name}")
    private String orderNameFieldApiName;

    @Value("${salesforce.order.amount.field:Amount}")
    private String orderAmountFieldApiName;

    @Value("${salesforce.order.description.field:Description}")
    private String orderDescriptionFieldApiName;

    @Value("${salesforce.order.email.field:}")
    private String orderEmailFieldApiName;

    @Value("${salesforce.order.first.name.field:}")
    private String orderFirstNameFieldApiName;

    @Value("${salesforce.order.last.name.field:}")
    private String orderLastNameFieldApiName;

    @Value("${salesforce.order.member.field:}")
    private String orderMemberFieldApiName;

    @Value("${salesforce.order.external.id.field:}")
    private String orderExternalIdFieldApiName;

    @Value("${salesforce.order.default.stage.name:Closed Won}")
    private String orderDefaultStageName;

    @Value("${salesforce.order.stage.field:StageName}")
    private String orderStageFieldApiName;

    @Value("${salesforce.order.close.date.field:CloseDate}")
    private String orderCloseDateFieldApiName;

    @Value("${salesforce.order.lead.source.field:LeadSource}")
    private String orderLeadSourceFieldApiName;

    @Value("${salesforce.token.store.enabled:true}")
    private boolean tokenStoreEnabled;

    @Value("${salesforce.token.store.path:.salesforce-token-store.json}")
    private String tokenStorePath;

    @Value("${salesforce.bootstrap.refresh.token:}")
    private String bootstrapRefreshToken;

    @Value("${salesforce.bootstrap.instance.url:}")
    private String bootstrapInstanceUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SalesforceService() {
        this.restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
    }

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
            loadTokensFromStore();
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

    /**
     * Whether {@code salesforce.bootstrap.refresh.token} / {@code SALESFORCE_BOOTSTRAP_REFRESH_TOKEN} was set.
     * Does not expose the token — useful to verify Railway env before redeploy.
     */
    public boolean isBootstrapRefreshTokenConfigured() {
        return bootstrapRefreshToken != null && !bootstrapRefreshToken.isBlank();
    }

    public boolean isBootstrapInstanceUrlConfigured() {
        return bootstrapInstanceUrl != null && !bootstrapInstanceUrl.isBlank();
    }

    public Map<String, String> exportTokenInfo(boolean includeSecrets) {
        Map<String, String> out = new HashMap<>();
        out.put("hasOAuthSession", String.valueOf(hasOAuthSession()));
        out.put("apiBaseUrl", apiBaseUrl());
        out.put("hasRefreshToken", String.valueOf(refreshToken != null && !refreshToken.isBlank()));
        out.put("hasAccessToken", String.valueOf(accessToken != null && !accessToken.isBlank()));
        if (includeSecrets) {
            out.put("refreshToken", refreshToken != null ? refreshToken : "");
            out.put("accessToken", accessToken != null ? accessToken : "");
        }
        return out;
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

    public Map<String, Object> discoverObjects(String nameContains, boolean includeFields, int limit) {
        Map<String, Object> result = new HashMap<>();
        result.put("apiVersion", apiVersion);
        result.put("instanceUrl", apiBaseUrl());

        if (demoMode) {
            List<Map<String, Object>> demoObjects = new ArrayList<>();
            demoObjects.add(demoObject("Contact", "Contact", false));
            demoObjects.add(demoObject("Opportunity", "Opportunity", false));
            demoObjects.add(demoObject("Woo_Order__c", "Woo Order", true));
            result.put("objects", demoObjects);
            result.put("count", demoObjects.size());
            return result;
        }

        String token = getAccessToken();
        String url = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<?, ?> body = response.getBody();

        List<Map<String, Object>> objects = new ArrayList<>();
        String needle = nameContains == null ? "" : nameContains.trim().toLowerCase();
        int max = limit > 0 ? limit : 200;

        if (body != null && body.get("sobjects") instanceof List<?> sobjects) {
            for (Object item : sobjects) {
                if (!(item instanceof Map<?, ?> obj)) continue;
                String apiName = obj.get("name") != null ? obj.get("name").toString() : "";
                String label = obj.get("label") != null ? obj.get("label").toString() : "";
                if (!needle.isBlank()) {
                    String haystack = (apiName + " " + label).toLowerCase();
                    if (!haystack.contains(needle)) continue;
                }

                Map<String, Object> one = new HashMap<>();
                one.put("name", apiName);
                one.put("label", label);
                one.put("custom", asBoolean(obj.get("custom")));
                one.put("createable", asBoolean(obj.get("createable")));
                one.put("updateable", asBoolean(obj.get("updateable")));
                one.put("queryable", asBoolean(obj.get("queryable")));

                if (includeFields && !apiName.isBlank()) {
                    one.put("fields", describeObjectFields(apiName, token));
                }

                objects.add(one);
            }
        }

        objects.sort(Comparator
                .comparing((Map<String, Object> o) -> !(Boolean.TRUE.equals(o.get("custom"))))
                .thenComparing(o -> o.get("name") != null ? o.get("name").toString() : ""));

        if (objects.size() > max) {
            objects = new ArrayList<>(objects.subList(0, max));
        }

        result.put("objects", objects);
        result.put("count", objects.size());
        return result;
    }

    public Map<String, Object> suggestMappings(String hint) {
        Map<String, Object> discovered = discoverObjects(hint, true, 300);
        Map<String, Object> result = new HashMap<>();
        result.put("apiVersion", apiVersion);
        result.put("instanceUrl", apiBaseUrl());

        Object objectsObj = discovered.get("objects");
        if (!(objectsObj instanceof List<?> objects)) {
            result.put("status", "no_objects");
            result.put("message", "No objects discovered.");
            return result;
        }

        Map<String, Object> contactObj = pickBestContactObject(objects);
        Map<String, Object> orderObj = pickBestOrderObject(objects);

        Map<String, Object> mapping = new HashMap<>();
        if (contactObj != null) {
            String contactObjectName = str(contactObj.get("name"));
            mapping.put("salesforce.contact.object", contactObjectName);
            mapping.put("salesforce.contact.external.id.field",
                    pickBestExternalIdField(contactObj, List.of("Email", "Email__c", "email", "email__c"), "Email"));
        } else {
            mapping.put("salesforce.contact.object", "Contact");
            mapping.put("salesforce.contact.external.id.field", "Email");
        }

        if (orderObj != null) {
            String orderObjectName = str(orderObj.get("name"));
            mapping.put("salesforce.order.object", orderObjectName);
            mapping.put("salesforce.order.name.field", pickField(orderObj, List.of("Name", "Order_Name__c"), "Name"));
            mapping.put("salesforce.order.amount.field", pickField(orderObj, List.of("Amount", "Amount__c", "Total__c"), "Amount"));
            mapping.put("salesforce.order.description.field",
                    pickField(orderObj, List.of("Description", "Description__c", "Notes__c"), "Description"));
            mapping.put("salesforce.order.email.field", pickField(orderObj, List.of("Email__c", "Email", "Customer_Email__c"), ""));
            mapping.put("salesforce.order.first.name.field", pickField(orderObj, List.of("First_Name__c", "FirstName__c"), ""));
            mapping.put("salesforce.order.last.name.field", pickField(orderObj, List.of("Last_Name__c", "LastName__c"), ""));
            mapping.put("salesforce.order.member.field", pickField(orderObj, List.of("IsMember__c", "Is_Member__c"), ""));
            mapping.put("salesforce.order.external.id.field",
                    pickBestExternalIdField(orderObj, List.of("Stripe_Session_Id__c", "Woo_Order_Id__c", "Order_Id__c"), ""));
            mapping.put("salesforce.order.stage.field", pickField(orderObj, List.of("StageName", "Stage__c"), ""));
            mapping.put("salesforce.order.close.date.field", pickField(orderObj, List.of("CloseDate", "Close_Date__c"), ""));
            mapping.put("salesforce.order.lead.source.field", pickField(orderObj, List.of("LeadSource", "Lead_Source__c"), ""));
        } else {
            mapping.put("salesforce.order.object", "Opportunity");
            mapping.put("salesforce.order.name.field", "Name");
            mapping.put("salesforce.order.amount.field", "Amount");
            mapping.put("salesforce.order.description.field", "Description");
            mapping.put("salesforce.order.external.id.field", "");
        }

        result.put("status", "ok");
        result.put("suggestedMapping", mapping);
        result.put("contactCandidate", contactObj);
        result.put("orderCandidate", orderObj);
        return result;
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
        String safeEmail = encodePathSegment(email);
        String url = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/"
                + contactObjectApiName + "/" + contactExternalIdField + "/" + safeEmail;

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
            log.warn("[SF] Contact upsert failed on configured object {}. Will try standard Contact fallback. Cause: {}",
                    contactObjectApiName, e.getMessage());
            return tryContactFallback(request, safeEmail, email);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. CREATE OPPORTUNITY
    // ─────────────────────────────────────────────────────────────────────────

    public String createOpportunity(String contactId, String eventTitle, String amount,
                                    String stripeSessionId, boolean isMember,
                                    String firstName, String lastName, String email) {
        log.info("[SF] Creating order object {} for contact: {}, event: {}", orderObjectApiName, contactId, eventTitle);

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
        String url = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/" + orderObjectApiName + "/";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> oppBody = new HashMap<>();
        putIfConfigured(oppBody, orderNameFieldApiName, "Event Registration - " + eventTitle);
        putIfConfigured(oppBody, orderAmountFieldApiName, amount);
        putIfConfigured(oppBody, orderDescriptionFieldApiName, "Stripe Session: " + stripeSessionId);
        putIfConfigured(oppBody, orderEmailFieldApiName, email);
        putIfConfigured(oppBody, orderFirstNameFieldApiName, firstName);
        putIfConfigured(oppBody, orderLastNameFieldApiName, lastName);
        putIfConfigured(oppBody, orderMemberFieldApiName, isMember);
        putIfConfigured(oppBody, orderStageFieldApiName, orderDefaultStageName);
        putIfConfigured(oppBody, orderCloseDateFieldApiName, java.time.LocalDate.now().toString());
        putIfConfigured(oppBody, orderLeadSourceFieldApiName, "Web");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(oppBody, headers);

        try {
            ResponseEntity<Map> response;
            if (isBlank(orderExternalIdFieldApiName) || isBlank(stripeSessionId)) {
                response = restTemplate.postForEntity(url, request, Map.class);
            } else {
                String upsertUrl = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/"
                        + orderObjectApiName + "/" + orderExternalIdFieldApiName + "/"
                        + encodePathSegment(stripeSessionId);
                response = restTemplate.exchange(upsertUrl, HttpMethod.PATCH, request, Map.class);
            }
            Map<?, ?> body = response.getBody();
            String oppId = body != null ? (String) body.get("id") : null;
            log.info("[SF] Opportunity created: {}", oppId);
            return oppId;

        } catch (Exception e) {
            log.warn("[SF] Order create/upsert failed on configured object {}. Will try Opportunity fallback. Cause: {}",
                    orderObjectApiName, e.getMessage());
            return tryOpportunityFallback(request);
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
        if (!demoMode && !hasOAuthSession()) {
            log.info("[SF] Order sync skipped for {} — OAuth not completed", email);
            return;
        }
        try {
            String contactId = upsertContact(firstName, lastName, email);
            String oppId = createOpportunity(contactId, eventTitle, amount, stripeSessionId, isMember,
                    firstName, lastName, email);

            if (demoMode) {
                log.info("[SF DEMO] ✅ Full order sync complete — Contact: {} | Opportunity: {} | Member: {}",
                        contactId, oppId, isMember);
            } else {
                if (oppId != null) {
                    log.info("[SF] Order synced to Salesforce for {} (record id: {})", email, oppId);
                } else {
                    log.info("[SF] Order sync finished for {} with no created record id", email);
                }
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
        persistTokens();
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
        persistTokens();
    }

    private synchronized void loadTokensFromStore() {
        // 1) Env bootstrap (best for Railway persistence across deploys)
        if (bootstrapRefreshToken != null && !bootstrapRefreshToken.isBlank()) {
            this.refreshToken = bootstrapRefreshToken.trim();
            if (bootstrapInstanceUrl != null && !bootstrapInstanceUrl.isBlank()) {
                this.effectiveInstanceUrl = stripTrailingSlash(bootstrapInstanceUrl.trim());
            }
            log.info("[SF] Loaded bootstrap refresh token from config");
        }

        // 2) File store fallback
        if (this.refreshToken == null || this.refreshToken.isBlank()) {
            readTokensFromFile();
        }

        // 3) Prime access token on startup if refresh token is available
        if (this.refreshToken != null && !this.refreshToken.isBlank()) {
            try {
                refreshAccessToken();
            } catch (Exception e) {
                log.warn("[SF] Failed to refresh token on startup: {}", e.getMessage());
            }
        }
    }

    private void readTokensFromFile() {
        if (!tokenStoreEnabled || tokenStorePath == null || tokenStorePath.isBlank()) return;
        try {
            Path path = Path.of(tokenStorePath);
            if (!Files.exists(path)) return;
            Map<?, ?> body = objectMapper.readValue(Files.readString(path), Map.class);
            Object rt = body.get("refreshToken");
            if (rt instanceof String s && !s.isBlank()) {
                this.refreshToken = s;
            }
            Object iu = body.get("instanceUrl");
            if (iu instanceof String s && !s.isBlank()) {
                this.effectiveInstanceUrl = stripTrailingSlash(s);
            }
            log.info("[SF] Loaded tokens from local token store");
        } catch (Exception e) {
            log.warn("[SF] Could not read token store {}: {}", tokenStorePath, e.getMessage());
        }
    }

    private void persistTokens() {
        if (!tokenStoreEnabled || tokenStorePath == null || tokenStorePath.isBlank()) return;
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("accessToken", accessToken);
            state.put("refreshToken", refreshToken);
            state.put("tokenExpiresAtMillis", tokenExpiresAtMillis);
            state.put("instanceUrl", effectiveInstanceUrl);
            Path path = Path.of(tokenStorePath);
            Files.writeString(path, objectMapper.writeValueAsString(state));
        } catch (Exception e) {
            log.warn("[SF] Could not persist token store {}: {}", tokenStorePath, e.getMessage());
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void putIfConfigured(Map<String, Object> body, String fieldApiName, Object value) {
        if (!isBlank(fieldApiName)) {
            body.put(fieldApiName, value);
        }
    }

    private List<Map<String, Object>> describeObjectFields(String objectApiName, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String url = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/"
                + encodePathSegment(objectApiName) + "/describe";
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<?, ?> body = response.getBody();
            List<Map<String, Object>> fields = new ArrayList<>();
            if (body != null && body.get("fields") instanceof List<?> fieldList) {
                for (Object f : fieldList) {
                    if (!(f instanceof Map<?, ?> field)) continue;
                    Map<String, Object> one = new HashMap<>();
                    one.put("name", field.get("name"));
                    one.put("label", field.get("label"));
                    one.put("type", field.get("type"));
                    one.put("externalId", asBoolean(field.get("externalId")));
                    one.put("createable", asBoolean(field.get("createable")));
                    one.put("updateable", asBoolean(field.get("updateable")));
                    fields.add(one);
                }
            }
            return fields;
        } catch (Exception e) {
            log.warn("[SF] Failed to describe object {}: {}", objectApiName, e.getMessage());
            return List.of();
        }
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b && b;
    }

    private static Map<String, Object> demoObject(String name, String label, boolean custom) {
        Map<String, Object> object = new HashMap<>();
        object.put("name", name);
        object.put("label", label);
        object.put("custom", custom);
        object.put("createable", true);
        object.put("updateable", true);
        object.put("queryable", true);
        return object;
    }

    private Map<String, Object> pickBestContactObject(List<?> objects) {
        Map<String, Object> standard = null;
        Map<String, Object> custom = null;
        for (Object item : objects) {
            if (!(item instanceof Map<?, ?> obj)) continue;
            String name = str(obj.get("name")).toLowerCase();
            String label = str(obj.get("label")).toLowerCase();
            if (name.equals("contact")) {
                standard = toStringObjectMap(obj);
            }
            if (name.contains("contact") || name.contains("customer") || label.contains("contact") || label.contains("customer")) {
                if (Boolean.TRUE.equals(obj.get("custom")) && custom == null) {
                    custom = toStringObjectMap(obj);
                }
            }
        }
        return custom != null ? custom : standard;
    }

    private Map<String, Object> pickBestOrderObject(List<?> objects) {
        Map<String, Object> opportunity = null;
        Map<String, Object> custom = null;
        for (Object item : objects) {
            if (!(item instanceof Map<?, ?> obj)) continue;
            String name = str(obj.get("name")).toLowerCase();
            String label = str(obj.get("label")).toLowerCase();
            if (name.equals("opportunity")) {
                opportunity = toStringObjectMap(obj);
            }
            boolean looksOrder = name.contains("order") || name.contains("woo") || name.contains("checkout")
                    || label.contains("order") || label.contains("woo") || label.contains("checkout");
            if (looksOrder && Boolean.TRUE.equals(obj.get("custom")) && custom == null) {
                custom = toStringObjectMap(obj);
            }
        }
        return custom != null ? custom : opportunity;
    }

    private String pickBestExternalIdField(Map<String, Object> objectDef, List<String> preferred, String fallback) {
        String preferredHit = pickField(objectDef, preferred, "");
        if (!preferredHit.isBlank()) return preferredHit;
        Object fieldsObj = objectDef.get("fields");
        if (fieldsObj instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                if (!(fieldObj instanceof Map<?, ?> field)) continue;
                boolean ext = Boolean.TRUE.equals(field.get("externalId"));
                if (ext && asBoolean(field.get("createable")) && asBoolean(field.get("updateable"))) {
                    return str(field.get("name"));
                }
            }
        }
        return fallback;
    }

    private String pickField(Map<String, Object> objectDef, List<String> preferredApiNames, String fallback) {
        Object fieldsObj = objectDef.get("fields");
        if (!(fieldsObj instanceof List<?> fields)) return fallback;
        for (String preferred : preferredApiNames) {
            for (Object fieldObj : fields) {
                if (!(fieldObj instanceof Map<?, ?> field)) continue;
                String name = str(field.get("name"));
                if (name.equalsIgnoreCase(preferred)) {
                    return name;
                }
            }
        }
        return fallback;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> input) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            out.put(str(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private String tryContactFallback(HttpEntity<Map<String, Object>> request, String safeEmail, String defaultContactId) {
        if ("Contact".equalsIgnoreCase(contactObjectApiName) && "Email".equalsIgnoreCase(contactExternalIdField)) {
            log.warn("[SF] Contact fallback skipped because configured object is already standard Contact/Email.");
            return defaultContactId;
        }
        try {
            String fallbackUrl = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/Contact/Email/" + safeEmail;
            ResponseEntity<Map> fallback = restTemplate.exchange(fallbackUrl, HttpMethod.PATCH, request, Map.class);
            Map<?, ?> body = fallback.getBody();
            String contactId = body != null && body.containsKey("id") ? (String) body.get("id") : defaultContactId;
            log.info("[SF] Contact fallback upsert succeeded on Contact/Email");
            return contactId;
        } catch (Exception fallbackEx) {
            log.warn("[SF] Contact fallback also failed. Continuing without Salesforce contact id: {}",
                    fallbackEx.getMessage());
            return defaultContactId;
        }
    }

    private String tryOpportunityFallback(HttpEntity<Map<String, Object>> request) {
        if ("Opportunity".equalsIgnoreCase(orderObjectApiName)) {
            log.warn("[SF] Opportunity fallback skipped because configured order object is already Opportunity.");
            return null;
        }
        try {
            String fallbackUrl = apiBaseUrl() + "/services/data/" + apiVersion + "/sobjects/Opportunity/";
            ResponseEntity<Map> fallback = restTemplate.postForEntity(fallbackUrl, request, Map.class);
            Map<?, ?> body = fallback.getBody();
            String oppId = body != null ? (String) body.get("id") : null;
            log.info("[SF] Opportunity fallback create succeeded: {}", oppId);
            return oppId;
        } catch (Exception fallbackEx) {
            log.warn("[SF] Opportunity fallback failed. Order sync will be skipped gracefully. Cause: {}",
                    fallbackEx.getMessage());
            return null;
        }
    }
}