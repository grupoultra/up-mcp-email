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
import net.ultrabase.mcp.email.gateway.TokenVault;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adds a new email account.
 * For Google (Gmail/Workspace): auto-detects via MX records, configures OAuth2, opens browser.
 * For others: requires server config.
 *
 * @author César Obach
 */
public class AddEmailAccount extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(AddEmailAccount.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Gmail IMAP/SMTP configuration
    private static final String GMAIL_IMAP_HOST = "imap.gmail.com";
    private static final int GMAIL_IMAP_PORT = 993;
    private static final String GMAIL_SMTP_HOST = "smtp.gmail.com";
    private static final int GMAIL_SMTP_PORT = 465;

    public AddEmailAccount(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "add_email_account";
    }

    @Override
    public String getDescription() {
        return "Add a new email account. For Google (Gmail/Workspace): auto-detects via MX records, " +
            "configures OAuth2, opens browser. For others: requires server config. " +
            "UI INTERACTION: For Google accounts, this tool opens a browser window for OAuth2 authorization. " +
            "The user must complete the authorization in the browser before the tool returns. " +
            "Inform the user BEFORE calling this tool.";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
            "\"email_address\":{\"type\":\"string\",\"description\":\"Email address (e.g., user@gmail.com, user@company.com)\"}," +
            "\"account_name\":{\"type\":\"string\",\"description\":\"Account name (optional, defaults to email address)\"}," +
            "\"full_name\":{\"type\":\"string\",\"description\":\"Display name for sent emails (optional, defaults to email prefix)\"}," +
            "\"password\":{\"type\":\"string\",\"description\":\"Password or App Password (required for non-Gmail accounts)\"}," +
            "\"imap_host\":{\"type\":\"string\",\"description\":\"IMAP server host (required for non-Gmail accounts)\"}," +
            "\"imap_port\":{\"type\":\"integer\",\"description\":\"IMAP server port (default: 993)\"}," +
            "\"smtp_host\":{\"type\":\"string\",\"description\":\"SMTP server host (required for non-Gmail accounts)\"}," +
            "\"smtp_port\":{\"type\":\"integer\",\"description\":\"SMTP server port (default: 465)\"}," +
            "\"default_from_address\":{\"type\":\"string\",\"description\":\"Default From address for sent emails. Use when the account authenticates with one address but should send as an alias (optional)\"}" +
            "},\"required\":[\"email_address\"]}";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String emailAddress = getString(args, "email_address");
            String accountName = getString(args, "account_name", emailAddress);
            String fullName = getString(args, "full_name", null);
            String defaultFromAddress = getString(args, "default_from_address", null);

            // Default full name from email prefix
            if (fullName == null || fullName.isEmpty()) {
                fullName = emailAddress.split("@")[0].replace(".", " ");
                // Capitalize words
                String[] words = fullName.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String word : words) {
                    if (sb.length() > 0) sb.append(" ");
                    if (!word.isEmpty()) {
                        sb.append(Character.toUpperCase(word.charAt(0)));
                        if (word.length() > 1) {
                            sb.append(word.substring(1).toLowerCase());
                        }
                    }
                }
                fullName = sb.toString();
            }

            // Check if account already exists
            if (context.accountRegistry().getAccountOrNull(accountName) != null) {
                throw new IllegalArgumentException("Account '" + accountName + "' already exists");
            }

            // Detect Google (Gmail or Workspace) and handle accordingly
            if (OAuthManager.isGoogleEmail(emailAddress)) {
                return addGmailAccountOAuth(emailAddress, accountName, fullName, defaultFromAddress);
            } else {
                return addGenericEmailAccount(args, emailAddress, accountName, fullName, defaultFromAddress);
            }
        });
    }

    private ObjectNode addGmailAccountOAuth(String emailAddress, String accountName, String fullName, String defaultFromAddress) {
        logger.info("Adding Gmail account with OAuth2: {}", emailAddress);

        // Get OAuth credentials
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
            // Run OAuth flow (opens browser), pinned to the mailbox being added so the user
            // cannot authorize the wrong Google account by mistake.
            OAuthManager.OAuthTokens tokens =
                OAuthManager.authorize(clientId, clientSecret, emailAddress);

            // Try to store tokens securely via Secret Management
            boolean tokensStoredSecurely = storeOAuthTokens(accountName, tokens);

            // Create account config
            AccountConfig config = new AccountConfig(accountName, emailAddress);
            config.setFullName(fullName);
            config.setImapHost(GMAIL_IMAP_HOST);
            config.setImapPort(GMAIL_IMAP_PORT);
            config.setImapSsl(true);
            config.setImapAuthMethod(AccountConfig.AuthMethod.OAUTH2);
            config.setSmtpHost(GMAIL_SMTP_HOST);
            config.setSmtpPort(GMAIL_SMTP_PORT);
            config.setSmtpSsl(true);
            config.setSmtpAuthMethod(AccountConfig.AuthMethod.OAUTH2);

            // Store tokens directly only if secure storage unavailable
            if (!tokensStoredSecurely) {
                config.setOauthAccessToken(tokens.accessToken());
                config.setOauthRefreshToken(tokens.refreshToken());
            }
            config.setOauthTokenExpiry(tokens.expiry());
            config.setOauthTokensInVault(tokensStoredSecurely);
            if (defaultFromAddress != null && !defaultFromAddress.isEmpty()) {
                config.setDefaultFromAddress(defaultFromAddress);
            }

            // Add and save
            context.accountRegistry().addAccount(config);
            context.accountRegistry().save();

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("account_name", accountName);
            result.put("email_address", emailAddress);
            result.put("auth_method", "oauth2");
            result.put("tokens_stored_securely", tokensStoredSecurely);
            result.put("message", String.format(
                "Gmail account '%s' added with OAuth2 authentication%s.",
                accountName,
                tokensStoredSecurely ? " (tokens stored securely)" : ""
            ));

            return result;

        } catch (OAuthManager.OAuthException e) {
            throw new RuntimeException("OAuth authorization failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode addGenericEmailAccount(Map<String, Object> args, String emailAddress,
                                               String accountName, String fullName, String defaultFromAddress) {
        logger.info("Adding generic email account: {}", emailAddress);

        // Validate required fields for non-Gmail
        String password = getString(args, "password", null);
        String imapHost = getString(args, "imap_host", null);
        String smtpHost = getString(args, "smtp_host", null);
        int imapPort = getInt(args, "imap_port", 993);
        int smtpPort = getInt(args, "smtp_port", 465);

        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required for non-Gmail accounts");
        }
        if (imapHost == null || imapHost.isEmpty()) {
            throw new IllegalArgumentException("IMAP host is required for non-Gmail accounts");
        }
        if (smtpHost == null || smtpHost.isEmpty()) {
            throw new IllegalArgumentException("SMTP host is required for non-Gmail accounts");
        }

        // Create account config
        AccountConfig config = new AccountConfig(accountName, emailAddress);
        config.setFullName(fullName);
        config.setImapHost(imapHost);
        config.setImapPort(imapPort);
        config.setImapSsl(true);
        config.setImapPassword(password);
        config.setImapAuthMethod(AccountConfig.AuthMethod.PASSWORD);
        config.setSmtpHost(smtpHost);
        config.setSmtpPort(smtpPort);
        config.setSmtpSsl(true);
        config.setSmtpPassword(password);
        config.setSmtpAuthMethod(AccountConfig.AuthMethod.PASSWORD);
        if (defaultFromAddress != null && !defaultFromAddress.isEmpty()) {
            config.setDefaultFromAddress(defaultFromAddress);
        }

        // Add and save
        context.accountRegistry().addAccount(config);
        context.accountRegistry().save();

        ObjectNode result = objectMapper.createObjectNode();
        result.put("success", true);
        result.put("account_name", accountName);
        result.put("email_address", emailAddress);
        result.put("auth_method", "password");
        result.put("message", String.format(
            "Email account '%s' added with password authentication.",
            accountName
        ));

        return result;
    }

    private boolean storeOAuthTokens(String accountName, OAuthManager.OAuthTokens tokens) {
        TokenVault vault = TokenVault.fromEnvironment();
        if (!vault.isAvailable()) {
            logger.debug("Secret Management not available, tokens will be stored in config");
            return false;
        }
        try {
            return vault.storeTokens(accountName, tokens);
        } catch (Exception e) {
            logger.warn("Failed to store tokens via Secret Management: {}", e.getMessage());
            return false;
        }
    }
}
