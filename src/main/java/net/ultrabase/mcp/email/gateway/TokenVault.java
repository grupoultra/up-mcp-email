/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.gateway;

import net.ultrabase.mcp.email.client.OAuthManager.OAuthTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Secure storage for OAuth2 tokens, backed by ultraPRO Desktop's Secret Management
 * (AES-256-GCM at rest). Centralizes the secret-key naming so storage and retrieval
 * stay in sync.
 *
 * <p>Secret keys per account are derived from the account alias:
 * {@code OAUTH_ACCESS_TOKEN_<safeName>}, {@code OAUTH_REFRESH_TOKEN_<safeName>},
 * {@code OAUTH_TOKEN_EXPIRY_<safeName>}.
 *
 * <p>Note: keys are derived from the account <em>alias</em>. Renaming an account
 * orphans its vault secrets — callers should re-store after a rename.
 *
 * @author César Obach
 */
public class TokenVault {

    private static final Logger logger = LoggerFactory.getLogger(TokenVault.class);

    private static final String PROVIDER_ID = "email";

    private final SecretClient client;

    private TokenVault(SecretClient client) {
        this.client = client;
    }

    /**
     * Creates a TokenVault from the provider environment. The returned vault is
     * always usable; call {@link #isAvailable()} to check whether the underlying
     * Secret Management backend is reachable.
     */
    public static TokenVault fromEnvironment() {
        return new TokenVault(SecretClient.fromEnvironment(PROVIDER_ID));
    }

    /** Returns true when the Secret Management backend is configured/reachable. */
    public boolean isAvailable() {
        return client != null;
    }

    /**
     * Converts an account alias into a filesystem/secret-safe key fragment.
     */
    public static String safeName(String accountName) {
        // Vault keys must satisfy the gateway's contract (EncryptedSecretStore.validateKey):
        // ^[A-Z][A-Z0-9_]*$ — uppercase letters, digits and underscores only. Uppercase the
        // alias and collapse any other character (@, ., -, …) to an underscore.
        return accountName.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }

    private static String accessKey(String accountName) {
        return "OAUTH_ACCESS_TOKEN_" + safeName(accountName);
    }

    private static String refreshKey(String accountName) {
        return "OAUTH_REFRESH_TOKEN_" + safeName(accountName);
    }

    private static String expiryKey(String accountName) {
        return "OAUTH_TOKEN_EXPIRY_" + safeName(accountName);
    }

    /**
     * Stores access/refresh/expiry for an account. A null refresh token is left
     * untouched in the vault (Google omits it on routine refreshes), preserving
     * the previously stored refresh token.
     *
     * @return true if every attempted write succeeded
     */
    public boolean storeTokens(String accountName, OAuthTokens tokens) {
        if (client == null) {
            return false;
        }
        boolean success = true;
        success &= client.storeSecret(accessKey(accountName), tokens.accessToken());
        if (tokens.refreshToken() != null && !tokens.refreshToken().isEmpty()) {
            success &= client.storeSecret(refreshKey(accountName), tokens.refreshToken());
        }
        if (tokens.expiry() != null) {
            success &= client.storeSecret(expiryKey(accountName), tokens.expiry().toString());
        }
        if (success) {
            logger.info("OAuth tokens stored securely for account '{}'", accountName);
        } else {
            logger.warn("Failed to fully store OAuth tokens for account '{}'", accountName);
        }
        return success;
    }

    /**
     * Loads tokens for an account from the vault.
     *
     * @return the stored tokens, or null if the backend is unavailable or the
     *         account has no access token stored
     */
    public OAuthTokens loadTokens(String accountName) {
        if (client == null) {
            return null;
        }
        String access = client.getSecret(accessKey(accountName));
        if (access == null || access.isEmpty()) {
            return null;
        }
        String refresh = client.getSecret(refreshKey(accountName));
        String expiryStr = client.getSecret(expiryKey(accountName));
        Instant expiry = null;
        if (expiryStr != null && !expiryStr.isEmpty()) {
            try {
                expiry = Instant.parse(expiryStr);
            } catch (Exception e) {
                logger.warn("Invalid expiry stored for account '{}': {}", accountName, expiryStr);
            }
        }
        return new OAuthTokens(access, refresh, expiry);
    }

    /** Removes all token secrets for an account (best-effort). */
    public void deleteTokens(String accountName) {
        if (client == null) {
            return;
        }
        client.deleteSecret(accessKey(accountName));
        client.deleteSecret(refreshKey(accountName));
        client.deleteSecret(expiryKey(accountName));
    }
}
