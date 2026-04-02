package com.stripe.checkout.controller;

import com.stripe.checkout.service.SalesforceService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Salesforce OAuth 2.0 Web Server flow (browser login).
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Admin or operator opens {@code GET /api/salesforce/authorize} in a browser → redirect to Salesforce login.</li>
 *   <li>After approval, Salesforce redirects to {@code /api/salesforce/callback?code=...&state=...}.</li>
 *   <li>This app exchanges {@code code} for an access token (and refresh token if the Connected App allows it).</li>
 * </ol>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /api/salesforce/authorize} — start OAuth (browser)</li>
 *   <li>{@code GET /api/salesforce/callback} — OAuth redirect URI (register this exact URL in the Connected App)</li>
 *   <li>{@code GET /api/salesforce/member?email=...}</li>
 *   <li>{@code POST /api/salesforce/sync-order}</li>
 *   <li>{@code GET /api/salesforce/health}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/salesforce")
public class SalesforceController {

    private static final String SESSION_OAUTH_STATE = "SF_OAUTH_STATE";

    private static final Logger log = LoggerFactory.getLogger(SalesforceController.class);

    @Autowired
    private SalesforceService salesforceService;

    @Value("${salesforce.client.id}")
    private String clientId;

    @Value("${salesforce.auth.url}")
    private String authUrl;

    @Value("${salesforce.callback.url}")
    private String callbackUrl;

    @Value("${salesforce.oauth.scope:api refresh_token}")
    private String oauthScope;

    @Value("${salesforce.instance.url}")
    private String instanceUrl;

    /**
     * Starts the OAuth browser flow — redirect to Salesforce authorize endpoint.
     */
    @GetMapping("/authorize")
    public void authorize(HttpSession session, HttpServletResponse response) throws IOException {
        String state = UUID.randomUUID().toString();
        session.setAttribute(SESSION_OAUTH_STATE, state);

        String redirectUri = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        String scopeEnc    = URLEncoder.encode(oauthScope.trim(), StandardCharsets.UTF_8);
        String stateEnc    = URLEncoder.encode(state, StandardCharsets.UTF_8);
        String clientEnc   = URLEncoder.encode(clientId, StandardCharsets.UTF_8);

        String base = authUrl.contains("?") ? authUrl.split("\\?")[0] : authUrl;
        String url = base
                + "?response_type=code"
                + "&client_id=" + clientEnc
                + "&redirect_uri=" + redirectUri
                + "&scope=" + scopeEnc
                + "&state=" + stateEnc;

        log.info("[SF] Redirecting to Salesforce authorize (state set in session)");
        response.sendRedirect(url);
    }

    @GetMapping("/callback")
    public ResponseEntity<Map<String, String>> oauthCallback(
            @RequestParam(value = "code",  required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDesc,
            HttpSession session) {

        log.info("[SF Callback] Received — code present: {}, error: {}", code != null, error);

        if (error != null) {
            log.error("[SF Callback] Salesforce returned error: {} — {}", error, errorDesc);
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("error",   error);
            resp.put("message", errorDesc != null ? errorDesc : "Salesforce authorisation denied");
            return ResponseEntity.status(400).body(resp);
        }

        String expectedState = (String) session.getAttribute(SESSION_OAUTH_STATE);
        boolean stateValid = expectedState != null && state != null && expectedState.equals(state);
        if (!stateValid) {
            // In-memory HttpSession state can be lost if Railway routes the callback to a different instance.
            // Do NOT block the OAuth token exchange solely on state validation; proceed when `code` is present.
            log.warn("[SF Callback] OAuth state mismatch/missing. expectedStatePresent={}, receivedStatePresent={}. Proceeding anyway.",
                    expectedState != null, state != null);
            if (expectedState != null) {
                session.removeAttribute(SESSION_OAUTH_STATE);
            }
        } else {
            session.removeAttribute(SESSION_OAUTH_STATE);
        }

        if (code == null || code.isBlank()) {
            // Opening /callback in the browser (no ?code=) is not an error when tokens already exist (e.g. bootstrap env).
            if (salesforceService.hasOAuthSession()) {
                Map<String, String> resp = new HashMap<>();
                resp.put("status",  "already_connected");
                resp.put("message", "No ?code= on this request — expected if you opened /callback directly. "
                        + "Salesforce only redirects here with ?code=... after you approve GET /api/salesforce/authorize. "
                        + "This server already has a valid session; use GET /api/salesforce/health.");
                return ResponseEntity.ok(resp);
            }
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("message", "No authorisation code from Salesforce. Start with GET /api/salesforce/authorize — "
                    + "do not open /callback alone; Salesforce appends ?code=... only after approval.");
            return ResponseEntity.status(400).body(resp);
        }

        try {
            Map<String, String> tokenData = salesforceService.exchangeCodeForToken(code);
            log.info("[SF Callback] Token exchange successful");

            Map<String, String> resp = new HashMap<>();
            resp.put("status",       "connected");
            resp.put("message",      "Salesforce connected successfully (OAuth). Server can call the Data API until tokens expire.");
            resp.put("instance_url", tokenData.get("instance_url"));
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("[SF Callback] Token exchange failed: {}", e.getMessage());
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("message", "Token exchange failed: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @GetMapping("/member")
    public ResponseEntity<Map<String, Object>> checkMember(@RequestParam String email) {
        log.info("[SF] Member check request for: {}", email);

        if (email == null || email.isBlank()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "email parameter is required");
            return ResponseEntity.badRequest().body(resp);
        }

        boolean isMember = salesforceService.isMember(email);

        Map<String, Object> resp = new HashMap<>();
        resp.put("email",    email);
        resp.put("isMember", isMember);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/discover-objects")
    public ResponseEntity<Map<String, Object>> discoverObjects(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "includeFields", defaultValue = "false") boolean includeFields,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (!salesforceService.hasOAuthSession()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "needs_authorization");
            resp.put("message", "OAuth not completed. Open GET /api/salesforce/authorize and approve access first.");
            return ResponseEntity.status(400).body(resp);
        }

        int safeLimit = Math.max(1, Math.min(limit, 500));
        try {
            Map<String, Object> result = salesforceService.discoverObjects(query, includeFields, safeLimit);
            result.put("status", "ok");
            result.put("query", query != null ? query : "");
            result.put("includeFields", includeFields);
            result.put("limit", safeLimit);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[SF] discover-objects failed: {}", e.getMessage());
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "error");
            resp.put("message", "Failed to discover Salesforce objects: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @GetMapping("/suggest-mapping")
    public ResponseEntity<Map<String, Object>> suggestMapping(
            @RequestParam(value = "q", required = false) String query) {
        if (!salesforceService.hasOAuthSession()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "needs_authorization");
            resp.put("message", "OAuth not completed. Open GET /api/salesforce/authorize and approve access first.");
            return ResponseEntity.status(400).body(resp);
        }
        try {
            Map<String, Object> result = salesforceService.suggestMappings(query);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[SF] suggest-mapping failed: {}", e.getMessage());
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "error");
            resp.put("message", "Failed to suggest mapping: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @GetMapping("/token-info")
    public ResponseEntity<Map<String, String>> tokenInfo(
            @RequestParam(value = "includeSecrets", defaultValue = "false") boolean includeSecrets) {
        Map<String, String> info = salesforceService.exportTokenInfo(includeSecrets);
        return ResponseEntity.ok(info);
    }

    @PostMapping("/sync-order")
    public ResponseEntity<Map<String, String>> syncOrder(@RequestBody Map<String, Object> body) {
        String firstName  = (String) body.getOrDefault("firstName",   "");
        String lastName   = (String) body.getOrDefault("lastName",    "");
        String email      = (String) body.getOrDefault("email",       "");
        String eventTitle = (String) body.getOrDefault("eventTitle",  "Event");
        String amount     = (String) body.getOrDefault("amount",      "0");
        String sessionId  = (String) body.getOrDefault("sessionId",   "");
        boolean isMember  = Boolean.TRUE.equals(body.get("isMember"));

        log.info("[SF] Manual sync-order for: {}", email);

        if (email.isBlank()) {
            Map<String, String> resp = new HashMap<>();
            resp.put("error", "email is required");
            return ResponseEntity.badRequest().body(resp);
        }

        try {
            salesforceService.syncOrderToSalesforce(firstName, lastName, email,
                    eventTitle, amount, sessionId, isMember);

            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "synced");
            resp.put("message", "Order synced to Salesforce for " + email);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("message", e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> resp = new HashMap<>();
        resp.put("instance_url_config", instanceUrl);
        resp.put("oauth_callback_url", callbackUrl);
        resp.put("authorize_path", "/api/salesforce/authorize");

        if (!salesforceService.hasOAuthSession()) {
            resp.put("status",  "needs_authorization");
            resp.put("bootstrap_refresh_token_env_set", String.valueOf(salesforceService.isBootstrapRefreshTokenConfigured()));
            resp.put("bootstrap_instance_url_env_set", String.valueOf(salesforceService.isBootstrapInstanceUrlConfigured()));
            resp.put("message", "No Salesforce OAuth session in this process. Either: (1) Set Railway secrets "
                    + "SALESFORCE_BOOTSTRAP_REFRESH_TOKEN and SALESFORCE_BOOTSTRAP_INSTANCE_URL (sandbox instance URL), "
                    + "then redeploy; or (2) Open GET /api/salesforce/authorize in a browser, approve the Connected App — "
                    + "note restarts clear in-memory tokens unless bootstrap env is set.");
            return ResponseEntity.ok(resp);
        }

        try {
            salesforceService.verifyDataApiReachable();
            resp.put("status",  "connected");
            resp.put("message", "Salesforce OAuth session is valid and Data API is reachable.");
            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            resp.put("status",  "needs_authorization");
            resp.put("message", e.getMessage());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("status",  "error");
            resp.put("message", "Token present but API check failed: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }
}
