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
        if (expectedState == null || state == null || !expectedState.equals(state)) {
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("message", "Invalid or missing OAuth state. Start again: GET /api/salesforce/authorize "
                    + "(same browser so the session cookie is sent on callback).");
            return ResponseEntity.status(403).body(resp);
        }
        session.removeAttribute(SESSION_OAUTH_STATE);

        if (code == null || code.isBlank()) {
            Map<String, String> resp = new HashMap<>();
            resp.put("status",  "error");
            resp.put("message", "No authorisation code received from Salesforce");
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
            resp.put("message", "OAuth not completed. Open GET /api/salesforce/authorize in a browser on this host, "
                    + "approve the Connected App, then try again. Tokens are stored in memory until the app restarts "
                    + "(use refresh_token in Connected App scopes for renewal).");
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
