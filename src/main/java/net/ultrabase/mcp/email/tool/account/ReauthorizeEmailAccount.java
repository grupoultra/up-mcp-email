/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.client.OAuthManager;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.gateway.SecretClient;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Re-authorizes an existing OAuth2 email account.
 * Opens browser for user consent and refreshes tokens without losing
 * account configuration (signature, permissions, etc.).
 *
 * @author César Obach
 */
public class ReauthorizeEmailAccount extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ReauthorizeEmailAccount.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public ReauthorizeEmailAccount(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "reauthorize_email_account";
    }

    @Override
    public String getDescription() {
        return "Re-authorize an existing OAuth2 email account when its token has expired or been revoked. " +
            "Opens a browser window for Google OAuth2 authorization. Only works with OAuth2 accounts (Gmail/Google Workspace). " +
            "Preserves all account settings (signature, permissions, display name). " +
            "UI INTERACTION: This tool opens a browser window. " +
            "The user must complete the authorization before the tool returns. " +
            "Inform the user BEFORE calling this tool.";
    }

    @Override
    public String getInputSchema() {
        return schemaEmpty();
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);

            // Verify account exists
            AccountConfig config = context.accountRegistry().getAccountOrNull(accountName);
            if (config == null) {
                throw new IllegalArgumentException("Account not found: " + accountName);
            }

            // Verify it's an OAuth2 account
            if (!config.isOAuth2()) {
                throw new IllegalArgumentException(
                    "Account '" + accountName + "' uses password authentication. " +
                    "Re-authorization is only supported for OAuth2 accounts (Gmail/Google Workspace). " +
                    "Use update_email_account to change the password instead."
                );
            }

            logger.info("Re-authorizing OAuth2 account: {}", accountName);

            // Load bundled OAuth credentials
            String[] credentials = OAuthManager.loadBundledCredentials();
            if (credentials == null) {
                throw new IllegalStateException(
                    "Gmail OAuth credentials not configured. " +
                    "Contact your administrator or configure custom OAuth credentials " +
                    "via ultraPRO Desktop's Secret Management."
                );
            }

            String clientId = credentials[0];
            String clientSecret = credentials[1];

            try {
                // Run OAuth flow (opens browser)
                OAuthManager.OAuthTokens tokens = OAuthManager.authorize(clientId, clientSecret);

                // Try to store tokens securely via Secret Management
                boolean tokensStoredSecurely = storeOAuthTokens(accountName, tokens);

                // Update account config with new tokens
                if (!tokensStoredSecurely) {
                    config.setOauthAccessToken(tokens.accessToken());
                    config.setOauthRefreshToken(tokens.refreshToken());
                }
                config.setOauthTokenExpiry(tokens.expiry());
                config.setOauthTokensInVault(tokensStoredSecurely);

                // Re-add to invalidate cached client (addAccount overwrites and clears cache)
                context.accountRegistry().addAccount(config);
                context.accountRegistry().save();

                ObjectNode result = objectMapper.createObjectNode();
                result.put("success", true);
                result.put("account_name", accountName);
                result.put("email_address", config.getEmailAddress());
                result.put("tokens_stored_securely", tokensStoredSecurely);
                result.put("message", String.format(
                    "Account '%s' re-authorized successfully%s.",
                    accountName,
                    tokensStoredSecurely ? " (tokens stored securely)" : ""
                ));

                return result;

            } catch (OAuthManager.OAuthException e) {
                throw new RuntimeException("OAuth re-authorization failed: " + e.getMessage(), e);
            }
        });
    }

    private boolean storeOAuthTokens(String accountName, OAuthManager.OAuthTokens tokens) {
        SecretClient client = SecretClient.fromEnvironment("email");
        if (client == null) {
            logger.debug("Secret Management not available, tokens will be stored in config");
            return false;
        }

        try {
            String safeName = accountName.replace("@", "_at_").replace(".", "_");
            boolean success = true;
            success &= client.storeSecret("OAUTH_ACCESS_TOKEN_" + safeName, tokens.accessToken());
            success &= client.storeSecret("OAUTH_REFRESH_TOKEN_" + safeName, tokens.refreshToken());
            success &= client.storeSecret("OAUTH_TOKEN_EXPIRY_" + safeName, tokens.expiry().toString());

            if (success) {
                logger.info("OAuth tokens stored securely for account '{}'", accountName);
            } else {
                logger.debug("Secret Management unavailable (HTTP 405), tokens will be stored in config");
            }
            return success;
        } catch (Exception e) {
            logger.warn("Failed to store tokens via Secret Management: {}", e.getMessage());
            return false;
        }
    }
}
