/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages OAuth2 authentication for Gmail/Google Workspace.
 * Handles authorization flow and token refresh.
 *
 * @author César Obach
 */
public class OAuthManager {

    private static final Logger logger = LoggerFactory.getLogger(OAuthManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Google OAuth endpoints
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    // Gmail scopes
    private static final String SCOPES = "https://mail.google.com/";

    // Local callback server
    private static final String REDIRECT_URI = "http://localhost:8089/oauth/callback";
    private static final int CALLBACK_PORT = 8089;

    // Track active server to ensure cleanup between consecutive calls
    private static volatile HttpServer activeServer;

    /**
     * OAuth tokens container.
     */
    public record OAuthTokens(
        String accessToken,
        String refreshToken,
        Instant expiry
    ) {}

    /**
     * Performs OAuth2 authorization flow for Gmail.
     * Opens browser for user consent and handles callback.
     *
     * @param clientId     OAuth client ID
     * @param clientSecret OAuth client secret
     * @return OAuth tokens
     * @throws OAuthException if authorization fails
     */
    public static OAuthTokens authorize(String clientId, String clientSecret) throws OAuthException {
        return authorize(clientId, clientSecret, null);
    }

    /**
     * Same as {@link #authorize(String, String)} but pins the flow to a specific Google
     * account. {@code expectedEmail} is passed to Google as {@code login_hint} so its account
     * chooser pre-selects the right mailbox, and the account actually authorized is verified
     * after the token exchange — a mismatch aborts (with a clear message) instead of silently
     * storing a token for the wrong account. Pass {@code null} to skip hint and verification.
     *
     * @param clientId     OAuth client ID
     * @param clientSecret OAuth client secret
     * @param expectedEmail the mailbox this authorization is for (e.g. cesar@ratio.cl), or null
     * @return OAuth tokens, guaranteed to belong to {@code expectedEmail} when it is non-null
     * @throws OAuthException if authorization fails or the authorized account does not match
     */
    public static OAuthTokens authorize(String clientId, String clientSecret, String expectedEmail)
            throws OAuthException {
        // Stop any lingering server from a previous call
        if (activeServer != null) {
            try {
                activeServer.stop(0);
                logger.debug("Stopped lingering OAuth callback server");
            } catch (Exception ignored) {}
            activeServer = null;
        }

        CompletableFuture<String> authCodeFuture = new CompletableFuture<>();
        HttpServer server = null;

        try {
            // Start local callback server
            server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
            HttpServer finalServer = server;

            server.createContext("/oauth/callback", exchange -> {
                try {
                    // Parse query parameters
                    String query = exchange.getRequestURI().getQuery();
                    Map<String, String> params = parseQueryParams(query);

                    if (params.containsKey("error")) {
                        String error = params.get("error");
                        String description = params.getOrDefault("error_description", "Unknown error");
                        sendResponse(exchange, 400, "Authorization failed: " + description);
                        authCodeFuture.completeExceptionally(new OAuthException("OAuth error: " + error + " - " + description));
                        return;
                    }

                    String code = params.get("code");
                    if (code == null) {
                        sendResponse(exchange, 400, "Missing authorization code");
                        authCodeFuture.completeExceptionally(new OAuthException("Missing authorization code"));
                        return;
                    }

                    // Success response — makes the target account explicit so a wrong-account
                    // sign-in is visible immediately (and is verified server-side below).
                    String accountBanner = (expectedEmail != null && !expectedEmail.isEmpty())
                        ? "<p style=\"font-size:16px;\">Account: <strong>" + escapeHtml(expectedEmail) + "</strong></p>"
                          + "<p style=\"color:#a00;\">If you signed in with a different Google account, "
                          + "this authorization will be rejected — re-run and sign in as the account above.</p>"
                        : "";
                    String successHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Authorization Successful</title></head>
                        <body style="font-family: sans-serif; text-align: center; padding: 50px;">
                            <h1>Authorization Successful!</h1>
                            %s
                            <p>You can close this window and return to your application.</p>
                        </body>
                        </html>
                        """.formatted(accountBanner);
                    sendResponse(exchange, 200, successHtml);
                    authCodeFuture.complete(code);

                } catch (Exception e) {
                    logger.error("Callback error: {}", e.getMessage(), e);
                    authCodeFuture.completeExceptionally(e);
                }
            });

            server.setExecutor(null);
            server.start();
            activeServer = server;
            logger.info("OAuth callback server started on port {}", CALLBACK_PORT);

            // Build authorization URL
            String authUrl = String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline&prompt=consent",
                AUTH_URL,
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8),
                URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)
            );

            // Pin Google's account chooser to the intended mailbox so the user does not pick
            // the wrong Google account by mistake.
            if (expectedEmail != null && !expectedEmail.isEmpty()) {
                authUrl += "&login_hint=" + URLEncoder.encode(expectedEmail, StandardCharsets.UTF_8);
            }

            // Open browser
            logger.info("Opening browser for OAuth authorization...");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(authUrl));
            } else {
                // Fallback for headless environments
                ProcessBuilder pb = new ProcessBuilder("open", authUrl);
                pb.start();
            }

            // Wait for authorization code (timeout after 5 minutes)
            String authCode = authCodeFuture.get(5, TimeUnit.MINUTES);
            logger.info("Received authorization code");

            // Exchange code for tokens
            OAuthTokens tokens = exchangeCodeForTokens(authCode, clientId, clientSecret);

            // Verify the mailbox we just authorized matches the one being configured. Catches
            // the "signed in with the wrong Google account" mistake before the token is ever
            // stored against the wrong account.
            if (expectedEmail != null && !expectedEmail.isEmpty()) {
                String authorizedEmail = fetchAuthorizedEmail(tokens.accessToken());
                if (authorizedEmail != null && !authorizedEmail.equalsIgnoreCase(expectedEmail)) {
                    throw new OAuthException(String.format(
                        "Account mismatch: you authorized '%s' but this account is configured for "
                        + "'%s'. Re-run the authorization and sign in as %s.",
                        authorizedEmail, expectedEmail, expectedEmail));
                }
                if (authorizedEmail == null) {
                    logger.warn("Could not verify the authorized account for {} (Gmail profile "
                        + "lookup failed); proceeding without account verification", expectedEmail);
                }
            }

            return tokens;

        } catch (Exception e) {
            if (e instanceof OAuthException) {
                throw (OAuthException) e;
            }
            throw new OAuthException("OAuth authorization failed: " + e.getMessage(), e);
        } finally {
            if (server != null) {
                server.stop(0);
                activeServer = null;
            }
        }
    }

    /**
     * Exchanges authorization code for access and refresh tokens.
     */
    private static OAuthTokens exchangeCodeForTokens(String code, String clientId, String clientSecret)
            throws OAuthException {
        try {
            String requestBody = String.format(
                "code=%s&client_id=%s&client_secret=%s&redirect_uri=%s&grant_type=authorization_code",
                URLEncoder.encode(code, StandardCharsets.UTF_8),
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8),
                URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
            );

            HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseBody;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }

            if (responseCode != 200) {
                logger.error("Token exchange failed: {} - {}", responseCode, responseBody);
                throw new OAuthException("Token exchange failed: " + responseBody);
            }

            JsonNode json = objectMapper.readTree(responseBody);

            String accessToken = json.get("access_token").asText();
            String refreshToken = json.has("refresh_token") ? json.get("refresh_token").asText() : null;
            int expiresIn = json.get("expires_in").asInt();
            Instant expiry = Instant.now().plusSeconds(expiresIn);

            logger.info("Successfully obtained OAuth tokens");
            return new OAuthTokens(accessToken, refreshToken, expiry);

        } catch (Exception e) {
            if (e instanceof OAuthException) {
                throw (OAuthException) e;
            }
            throw new OAuthException("Failed to exchange code for tokens: " + e.getMessage(), e);
        }
    }

    /**
     * Refreshes an expired access token using the refresh token.
     *
     * @param refreshToken OAuth refresh token
     * @param clientId     OAuth client ID
     * @param clientSecret OAuth client secret
     * @return New OAuth tokens
     * @throws OAuthException if refresh fails
     */
    public static OAuthTokens refreshAccessToken(String refreshToken, String clientId, String clientSecret)
            throws OAuthException {
        try {
            String requestBody = String.format(
                "refresh_token=%s&client_id=%s&client_secret=%s&grant_type=refresh_token",
                URLEncoder.encode(refreshToken, StandardCharsets.UTF_8),
                URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
            );

            HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseBody;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }

            if (responseCode != 200) {
                logger.error("Token refresh failed: {} - {}", responseCode, responseBody);
                throw new OAuthException("Token refresh failed: " + responseBody);
            }

            JsonNode json = objectMapper.readTree(responseBody);

            String accessToken = json.get("access_token").asText();
            // Refresh token is not always returned on refresh
            String newRefreshToken = json.has("refresh_token")
                ? json.get("refresh_token").asText()
                : refreshToken;
            int expiresIn = json.get("expires_in").asInt();
            Instant expiry = Instant.now().plusSeconds(expiresIn);

            logger.info("Successfully refreshed OAuth token");
            return new OAuthTokens(accessToken, newRefreshToken, expiry);

        } catch (Exception e) {
            if (e instanceof OAuthException) {
                throw (OAuthException) e;
            }
            throw new OAuthException("Failed to refresh token: " + e.getMessage(), e);
        }
    }

    /**
     * Loads OAuth credentials from bundled file or environment.
     *
     * @return Tuple of (clientId, clientSecret) or null if not found
     */
    public static String[] loadBundledCredentials() {
        // Search paths for bundled OAuth credentials
        Path[] searchPaths = {
            Path.of("/Applications/ultraPRO Desktop.app/Contents/Resources/providers/oauth_defaults.json"),
            Path.of(System.getProperty("user.home"), ".config", "ultrapro", "email", "oauth_defaults.json"),
        };

        for (Path path : searchPaths) {
            if (Files.exists(path)) {
                try {
                    String content = Files.readString(path);
                    JsonNode json = objectMapper.readTree(content);

                    // Support Google's native format (nested under "installed" or "web")
                    JsonNode creds = json;
                    if (json.has("installed")) {
                        creds = json.get("installed");
                    } else if (json.has("web")) {
                        creds = json.get("web");
                    }

                    String clientId = creds.has("client_id") ? creds.get("client_id").asText() : null;
                    String clientSecret = creds.has("client_secret") ? creds.get("client_secret").asText() : null;

                    if (clientId != null && clientSecret != null) {
                        logger.info("Loaded OAuth credentials from {}", path);
                        return new String[] { clientId, clientSecret };
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load OAuth credentials from {}: {}", path, e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * Checks if an email address uses Google (Gmail or Google Workspace).
     */
    public static boolean isGoogleEmail(String emailAddress) {
        if (emailAddress == null) {
            return false;
        }

        // Direct Gmail addresses
        if (emailAddress.endsWith("@gmail.com") || emailAddress.endsWith("@googlemail.com")) {
            return true;
        }

        // Check domain MX records for Google Workspace
        String domain = emailAddress.substring(emailAddress.indexOf('@') + 1);
        return isGoogleWorkspaceDomain(domain);
    }

    /**
     * Checks if a domain uses Google Workspace by checking MX records.
     */
    private static boolean isGoogleWorkspaceDomain(String domain) {
        try {
            // Use dnsjava for MX lookup
            org.xbill.DNS.Record[] records = new org.xbill.DNS.Lookup(domain, org.xbill.DNS.Type.MX).run();
            if (records != null) {
                for (org.xbill.DNS.Record record : records) {
                    if (record instanceof org.xbill.DNS.MXRecord) {
                        String mxHost = ((org.xbill.DNS.MXRecord) record).getTarget().toString().toLowerCase();
                        if (mxHost.contains("google.com") || mxHost.contains("googlemail.com")
                            || mxHost.contains("aspmx.l.google.com")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("MX lookup failed for {}: {}", domain, e.getMessage());
        }
        return false;
    }

    /**
     * Returns the email address the given access token authorizes, via the Gmail profile
     * endpoint (covered by the mail.google.com scope), or null if it cannot be determined.
     */
    private static String fetchAuthorizedEmail(String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URL("https://gmail.googleapis.com/gmail/v1/users/me/profile").openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code != 200) {
                logger.warn("Gmail profile lookup returned HTTP {}", code);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                JsonNode json = objectMapper.readTree(sb.toString());
                return json.has("emailAddress") ? json.get("emailAddress").asText() : null;
            }
        } catch (Exception e) {
            logger.warn("Gmail profile lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }

        Map<String, String> params = new java.util.HashMap<>();
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2) {
                params.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                );
            }
        }
        return params;
    }

    private static void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", body.startsWith("<!DOCTYPE") ? "text/html" : "text/plain");
        exchange.sendResponseHeaders(code, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    /**
     * Exception for OAuth-related errors.
     */
    public static class OAuthException extends Exception {
        public OAuthException(String message) {
            super(message);
        }

        public OAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
