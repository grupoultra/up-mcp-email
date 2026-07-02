/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.config;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Configuration for a single email account.
 *
 * @author César Obach
 */
public class AccountConfig {

    /**
     * Authentication method.
     */
    public enum AuthMethod {
        PASSWORD,
        OAUTH2
    }

    /**
     * CRUDLEX permissions for email operations.
     */
    public enum Permission {
        CREATE,   // Not used for email
        READ,     // Read email content
        UPDATE,   // Mark as read/unread, set flags
        DELETE,   // Delete emails
        LIST,     // List emails, check unread
        EXPORT,   // Download attachments
        EXECUTE   // Send emails
    }

    private String accountName;
    private String emailAddress;
    private String fullName;

    // IMAP settings
    private String imapHost;
    private int imapPort = 993;
    private boolean imapSsl = true;
    private String imapUsername;
    private String imapPassword;
    private AuthMethod imapAuthMethod = AuthMethod.PASSWORD;

    // SMTP settings
    private String smtpHost;
    private int smtpPort = 465;
    private boolean smtpSsl = true;
    private String smtpUsername;
    private String smtpPassword;
    private AuthMethod smtpAuthMethod = AuthMethod.PASSWORD;

    // OAuth2 tokens (for Gmail/Google Workspace)
    private String oauthAccessToken;
    private String oauthRefreshToken;
    private Instant oauthTokenExpiry;
    private boolean oauthTokensInVault = false;

    // Runtime-only (never persisted): the refresh token was rejected by the provider with a
    // terminal error (invalid_grant: expired/revoked). Retrying cannot cure it — the account
    // needs an interactive re-authorization. Set by EmailClient on refresh failure; cleared by
    // ReauthorizeEmailAccount. Keeps the keep-alive sweep from hammering a dead token and lets
    // status tools surface the condition instead of failing silently every 10 minutes.
    private volatile boolean oauthReauthRequired = false;
    private volatile Instant oauthReauthSince = null;

    // Permissions (default: all allowed)
    private Set<Permission> permissions = EnumSet.allOf(Permission.class);

    // Status reporting settings
    private boolean includeInStatus = false;
    private Integer statusCacheTtl = null;

    // Default flag
    private boolean isDefault = false;

    // Signature and footer settings
    private String signature;           // Personal signature for emails (nullable)
    private boolean includeFooter = true;  // Include "Sent vía ultraPRO" footer (default: true)
    private String signatureImagePath;  // Absolute path to signature logo image (nullable)

    // Default From address override (nullable - uses emailAddress if not set)
    private String defaultFromAddress;

    public AccountConfig() {}

    public AccountConfig(String accountName, String emailAddress) {
        this.accountName = accountName;
        this.emailAddress = emailAddress;
        this.imapUsername = emailAddress;
        this.smtpUsername = emailAddress;
    }

    // Getters and setters

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public void setImapPort(int imapPort) {
        this.imapPort = imapPort;
    }

    public boolean isImapSsl() {
        return imapSsl;
    }

    public void setImapSsl(boolean imapSsl) {
        this.imapSsl = imapSsl;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public void setImapUsername(String imapUsername) {
        this.imapUsername = imapUsername;
    }

    public String getImapPassword() {
        return imapPassword;
    }

    public void setImapPassword(String imapPassword) {
        this.imapPassword = imapPassword;
    }

    public AuthMethod getImapAuthMethod() {
        return imapAuthMethod;
    }

    public void setImapAuthMethod(AuthMethod imapAuthMethod) {
        this.imapAuthMethod = imapAuthMethod;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public boolean isSmtpSsl() {
        return smtpSsl;
    }

    public void setSmtpSsl(boolean smtpSsl) {
        this.smtpSsl = smtpSsl;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public void setSmtpPassword(String smtpPassword) {
        this.smtpPassword = smtpPassword;
    }

    public AuthMethod getSmtpAuthMethod() {
        return smtpAuthMethod;
    }

    public void setSmtpAuthMethod(AuthMethod smtpAuthMethod) {
        this.smtpAuthMethod = smtpAuthMethod;
    }

    public String getOauthAccessToken() {
        return oauthAccessToken;
    }

    public void setOauthAccessToken(String oauthAccessToken) {
        this.oauthAccessToken = oauthAccessToken;
    }

    public String getOauthRefreshToken() {
        return oauthRefreshToken;
    }

    public void setOauthRefreshToken(String oauthRefreshToken) {
        this.oauthRefreshToken = oauthRefreshToken;
    }

    public Instant getOauthTokenExpiry() {
        return oauthTokenExpiry;
    }

    public void setOauthTokenExpiry(Instant oauthTokenExpiry) {
        this.oauthTokenExpiry = oauthTokenExpiry;
    }

    public boolean isOauthTokensInVault() {
        return oauthTokensInVault;
    }

    public void setOauthTokensInVault(boolean oauthTokensInVault) {
        this.oauthTokensInVault = oauthTokensInVault;
    }

    public boolean isOauthReauthRequired() {
        return oauthReauthRequired;
    }

    public Instant getOauthReauthSince() {
        return oauthReauthSince;
    }

    /** Marks the account as needing interactive re-authorization (terminal refresh failure). */
    public void markOauthReauthRequired() {
        this.oauthReauthRequired = true;
        if (this.oauthReauthSince == null) {
            this.oauthReauthSince = Instant.now();
        }
    }

    /** Clears the re-authorization flag (called after a successful reauthorize). */
    public void clearOauthReauthRequired() {
        this.oauthReauthRequired = false;
        this.oauthReauthSince = null;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean isIncludeInStatus() {
        return includeInStatus;
    }

    public void setIncludeInStatus(boolean includeInStatus) {
        this.includeInStatus = includeInStatus;
    }

    public Integer getStatusCacheTtl() {
        return statusCacheTtl;
    }

    public void setStatusCacheTtl(Integer statusCacheTtl) {
        this.statusCacheTtl = statusCacheTtl;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public boolean isIncludeFooter() {
        return includeFooter;
    }

    public void setIncludeFooter(boolean includeFooter) {
        this.includeFooter = includeFooter;
    }

    public String getSignatureImagePath() {
        return signatureImagePath;
    }

    public void setSignatureImagePath(String signatureImagePath) {
        this.signatureImagePath = signatureImagePath;
    }

    public String getDefaultFromAddress() {
        return defaultFromAddress;
    }

    public void setDefaultFromAddress(String defaultFromAddress) {
        this.defaultFromAddress = defaultFromAddress;
    }

    /**
     * Returns true if this account uses OAuth2 authentication.
     */
    public boolean isOAuth2() {
        return imapAuthMethod == AuthMethod.OAUTH2;
    }

    /**
     * Returns a masked version of this config (for display purposes).
     */
    public AccountConfig masked() {
        AccountConfig masked = new AccountConfig();
        masked.accountName = this.accountName;
        masked.emailAddress = this.emailAddress;
        masked.fullName = this.fullName;
        masked.imapHost = this.imapHost;
        masked.imapPort = this.imapPort;
        masked.imapSsl = this.imapSsl;
        masked.imapUsername = this.imapUsername;
        masked.imapPassword = this.imapPassword != null ? "****" : null;
        masked.imapAuthMethod = this.imapAuthMethod;
        masked.smtpHost = this.smtpHost;
        masked.smtpPort = this.smtpPort;
        masked.smtpSsl = this.smtpSsl;
        masked.smtpUsername = this.smtpUsername;
        masked.smtpPassword = this.smtpPassword != null ? "****" : null;
        masked.smtpAuthMethod = this.smtpAuthMethod;
        masked.oauthAccessToken = this.oauthAccessToken != null ? "****" : null;
        masked.oauthRefreshToken = this.oauthRefreshToken != null ? "****" : null;
        masked.oauthTokenExpiry = this.oauthTokenExpiry;
        masked.oauthTokensInVault = this.oauthTokensInVault;
        masked.oauthReauthRequired = this.oauthReauthRequired;
        masked.oauthReauthSince = this.oauthReauthSince;
        masked.permissions = this.permissions;
        masked.includeInStatus = this.includeInStatus;
        masked.statusCacheTtl = this.statusCacheTtl;
        masked.isDefault = this.isDefault;
        masked.signature = this.signature;
        masked.includeFooter = this.includeFooter;
        masked.signatureImagePath = this.signatureImagePath;
        masked.defaultFromAddress = this.defaultFromAddress;
        return masked;
    }

    @Override
    public String toString() {
        return "AccountConfig{" +
            "accountName='" + accountName + '\'' +
            ", emailAddress='" + emailAddress + '\'' +
            ", authMethod=" + imapAuthMethod +
            ", isDefault=" + isDefault +
            '}';
    }
}
