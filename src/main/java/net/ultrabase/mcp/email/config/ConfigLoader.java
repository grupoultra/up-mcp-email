/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moandjiezana.toml.Toml;
import net.ultrabase.mcp.email.client.OAuthManager.OAuthTokens;
import net.ultrabase.mcp.email.gateway.TokenVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

/**
 * Loads and saves email account configuration.
 * Supports TOML format (compatible with Python provider) and JSON.
 *
 * Config location: ~/.config/ultrapro/email/config.toml
 *
 * @author César Obach
 */
public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"),
        ".config", "ultrapro", "email");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.toml");
    private static final Path CONFIG_FILE_JSON = CONFIG_DIR.resolve("config.json");

    /**
     * Loads account configuration from disk.
     *
     * @return Loaded registry
     */
    public static AccountRegistry load() {
        AccountRegistry registry = new AccountRegistry();

        // JSON is the canonical store. TOML is a legacy format kept only so existing
        // installations migrate forward on first run.
        boolean jsonCanonical = false;
        if (Files.exists(CONFIG_FILE_JSON)) {
            try {
                loadFromJson(CONFIG_FILE_JSON, registry);
                logger.info("Loaded configuration from {}", CONFIG_FILE_JSON);
            } catch (Exception e) {
                logger.warn("Failed to load JSON config: {}", e.getMessage());
            }
            jsonCanonical = registry.hasAccounts();
        }

        // Fall back to (and migrate from) legacy TOML only when JSON yielded nothing.
        boolean migratedFromToml = false;
        if (!registry.hasAccounts() && Files.exists(CONFIG_FILE)) {
            try {
                loadFromToml(CONFIG_FILE, registry);
                logger.info("Loaded legacy configuration from {}", CONFIG_FILE);
            } catch (Exception e) {
                logger.warn("Failed to load TOML config: {}", e.getMessage());
            }
            migratedFromToml = registry.hasAccounts();
        }

        if (!registry.hasAccounts()) {
            logger.info("No accounts configured in {}", CONFIG_DIR);
            return registry;
        }

        // Reconcile OAuth tokens with the secure vault: hydrate vault-backed accounts
        // and migrate any plaintext tokens into the vault.
        boolean vaultChanged = reconcileVault(registry);

        // Persist to canonical JSON whenever the in-memory registry diverged from disk.
        boolean jsonPersisted = jsonCanonical;
        if (migratedFromToml || vaultChanged) {
            try {
                save(registry);
                jsonPersisted = true;
            } catch (Exception e) {
                logger.warn("Failed to persist reconciled configuration: {}", e.getMessage());
            }
        }

        // Retire the legacy TOML only once the canonical JSON safely holds the data,
        // so it can never shadow the JSON on a future start.
        if (jsonPersisted && Files.exists(CONFIG_FILE)) {
            retireLegacyTomlFile();
        }

        return registry;
    }

    /**
     * Reconciles each OAuth account's tokens with the secure vault.
     *
     * <ul>
     *   <li>Vault-backed accounts ({@code oauth_tokens_in_vault=true}) are hydrated
     *       in-memory from the vault.</li>
     *   <li>Legacy accounts that still carry plaintext tokens are migrated into the
     *       vault (tokens stored securely, plaintext cleared, flag set).</li>
     * </ul>
     *
     * @return true if the registry changed and should be persisted
     */
    private static boolean reconcileVault(AccountRegistry registry) {
        TokenVault vault = TokenVault.fromEnvironment();
        boolean changed = false;

        for (AccountConfig config : registry.getAccounts()) {
            if (!config.isOAuth2()) {
                continue;
            }
            String name = config.getAccountName();

            if (config.isOauthTokensInVault()) {
                // Hydrate runtime token fields from the vault (not persisted to JSON).
                OAuthTokens tokens = vault.loadTokens(name);
                if (tokens != null) {
                    config.setOauthAccessToken(tokens.accessToken());
                    if (tokens.refreshToken() != null) {
                        config.setOauthRefreshToken(tokens.refreshToken());
                    }
                    if (tokens.expiry() != null) {
                        config.setOauthTokenExpiry(tokens.expiry());
                    }
                } else {
                    logger.warn("Account '{}' marked as vault-backed but tokens could not be "
                        + "loaded from Secret Management (vault unavailable or empty)", name);
                }
                continue;
            }

            // Legacy plaintext account: migrate into the vault if we have tokens and a vault.
            if (vault.isAvailable()
                && config.getOauthAccessToken() != null && !config.getOauthAccessToken().isEmpty()
                && config.getOauthRefreshToken() != null && !config.getOauthRefreshToken().isEmpty()) {

                OAuthTokens tokens = new OAuthTokens(
                    config.getOauthAccessToken(),
                    config.getOauthRefreshToken(),
                    config.getOauthTokenExpiry());

                if (vault.storeTokens(name, tokens)) {
                    config.setOauthTokensInVault(true);
                    config.setOauthAccessToken(null);
                    config.setOauthRefreshToken(null);
                    changed = true;
                    logger.info("Migrated plaintext OAuth tokens for account '{}' into the vault", name);
                } else {
                    logger.warn("Could not migrate account '{}' into the vault; keeping plaintext "
                        + "tokens in config", name);
                }
            }
        }

        return changed;
    }

    /**
     * Renames the legacy {@code config.toml} to {@code config.toml.migrated} so the
     * canonical JSON is never shadowed again. Best-effort: logs and continues on failure.
     */
    private static void retireLegacyTomlFile() {
        Path retired = CONFIG_DIR.resolve("config.toml.migrated");
        try {
            Files.move(CONFIG_FILE, retired, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Retired legacy TOML config to {}", retired);
        } catch (Exception e) {
            logger.warn("Failed to retire legacy TOML config {}: {}", CONFIG_FILE, e.getMessage());
        }
    }

    /**
     * Saves account configuration to disk.
     *
     * @param registry Registry to save
     */
    public static void save(AccountRegistry registry) {
        try {
            Files.createDirectories(CONFIG_DIR);
            saveToJson(CONFIG_FILE_JSON, registry);
            logger.info("Saved configuration to {}", CONFIG_FILE_JSON);
        } catch (Exception e) {
            logger.error("Failed to save config: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    /**
     * Loads configuration from TOML file (Python-compatible format).
     */
    private static void loadFromToml(Path file, AccountRegistry registry) throws IOException {
        Toml toml = new Toml().read(file.toFile());

        // Get accounts table
        Toml accountsTable = toml.getTable("accounts");
        if (accountsTable == null) {
            return;
        }

        // Get default account name
        String defaultAccount = toml.getString("default_account");

        // Parse each account
        for (Map.Entry<String, Object> entry : accountsTable.entrySet()) {
            String accountName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }

            Toml accountToml = accountsTable.getTable(accountName);
            if (accountToml == null) {
                continue;
            }

            AccountConfig config = parseTomlAccount(accountName, accountToml);
            if (accountName.equals(defaultAccount)) {
                config.setDefault(true);
            }
            registry.addAccount(config);
        }
    }

    /**
     * Parses a single account from TOML.
     */
    private static AccountConfig parseTomlAccount(String accountName, Toml toml) {
        AccountConfig config = new AccountConfig();
        config.setAccountName(accountName);
        config.setEmailAddress(toml.getString("email_address"));
        config.setFullName(toml.getString("full_name"));

        // Auth method
        String authMethod = toml.getString("auth_method", "password");
        AccountConfig.AuthMethod auth = "oauth2".equalsIgnoreCase(authMethod)
            ? AccountConfig.AuthMethod.OAUTH2 : AccountConfig.AuthMethod.PASSWORD;
        config.setImapAuthMethod(auth);
        config.setSmtpAuthMethod(auth);

        // Incoming (IMAP) settings
        Toml incoming = toml.getTable("incoming");
        if (incoming != null) {
            config.setImapHost(incoming.getString("host"));
            config.setImapPort(incoming.getLong("port", 993L).intValue());
            config.setImapSsl(incoming.getBoolean("use_ssl", true));
            config.setImapUsername(incoming.getString("user_name", config.getEmailAddress()));
            config.setImapPassword(incoming.getString("password"));

            // OAuth tokens
            config.setOauthAccessToken(incoming.getString("oauth_access_token"));
            config.setOauthRefreshToken(incoming.getString("oauth_refresh_token"));
            String expiry = incoming.getString("oauth_token_expiry");
            if (expiry != null) {
                config.setOauthTokenExpiry(Instant.parse(expiry));
            }
            config.setOauthTokensInVault(incoming.getBoolean("oauth_tokens_in_vault", false));
        }

        // Outgoing (SMTP) settings
        Toml outgoing = toml.getTable("outgoing");
        if (outgoing != null) {
            config.setSmtpHost(outgoing.getString("host"));
            config.setSmtpPort(outgoing.getLong("port", 465L).intValue());
            config.setSmtpSsl(outgoing.getBoolean("use_ssl", true));
            config.setSmtpUsername(outgoing.getString("user_name", config.getEmailAddress()));
            config.setSmtpPassword(outgoing.getString("password"));
        }

        // Permissions
        String permissions = toml.getString("permissions");
        if (permissions != null) {
            config.setPermissions(parsePermissions(permissions));
        }

        // Status settings
        config.setIncludeInStatus(toml.getBoolean("include_in_status", false));
        Long cacheTtl = toml.getLong("status_cache_ttl");
        if (cacheTtl != null) {
            config.setStatusCacheTtl(cacheTtl.intValue());
        }

        return config;
    }

    /**
     * Parses permission string (e.g., "READ|LIST|UPDATE" or "FULL").
     */
    private static Set<AccountConfig.Permission> parsePermissions(String perms) {
        if (perms == null || perms.isBlank()) {
            return EnumSet.allOf(AccountConfig.Permission.class);
        }

        // Handle aliases
        switch (perms.toUpperCase()) {
            case "FULL":
                return EnumSet.allOf(AccountConfig.Permission.class);
            case "READONLY":
                return EnumSet.of(AccountConfig.Permission.LIST, AccountConfig.Permission.READ);
            case "SAFE":
                return EnumSet.of(AccountConfig.Permission.LIST, AccountConfig.Permission.READ,
                    AccountConfig.Permission.UPDATE);
            case "NO_SEND":
                EnumSet<AccountConfig.Permission> noSend = EnumSet.allOf(AccountConfig.Permission.class);
                noSend.remove(AccountConfig.Permission.EXECUTE);
                return noSend;
            case "NO_DELETE":
                EnumSet<AccountConfig.Permission> noDelete = EnumSet.allOf(AccountConfig.Permission.class);
                noDelete.remove(AccountConfig.Permission.DELETE);
                return noDelete;
        }

        // Parse pipe-separated values
        Set<AccountConfig.Permission> result = EnumSet.noneOf(AccountConfig.Permission.class);
        for (String p : perms.split("\\|")) {
            try {
                result.add(AccountConfig.Permission.valueOf(p.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown permission: {}", p);
            }
        }
        return result;
    }

    /**
     * Formats permissions for display.
     */
    public static String formatPermissions(Set<AccountConfig.Permission> perms) {
        if (perms.equals(EnumSet.allOf(AccountConfig.Permission.class))) {
            return "FULL";
        }
        StringJoiner joiner = new StringJoiner("|");
        for (AccountConfig.Permission p : perms) {
            joiner.add(p.name());
        }
        return joiner.toString();
    }

    /**
     * Loads configuration from JSON file.
     */
    private static void loadFromJson(Path file, AccountRegistry registry) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        Map<String, Object> data = mapper.readValue(file.toFile(), Map.class);

        String defaultAccount = (String) data.get("default_account");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) data.get("accounts");

        if (accounts != null) {
            for (Map<String, Object> accountData : accounts) {
                AccountConfig config = parseJsonAccount(accountData);
                if (config.getAccountName().equals(defaultAccount)) {
                    config.setDefault(true);
                }
                registry.addAccount(config);
            }
        }
    }

    /**
     * Parses a single account from JSON.
     */
    private static AccountConfig parseJsonAccount(Map<String, Object> data) {
        AccountConfig config = new AccountConfig();
        config.setAccountName((String) data.get("account_name"));
        config.setEmailAddress((String) data.get("email_address"));
        config.setFullName((String) data.get("full_name"));

        // Auth method
        String authMethod = (String) data.getOrDefault("auth_method", "password");
        AccountConfig.AuthMethod auth = "oauth2".equalsIgnoreCase(authMethod)
            ? AccountConfig.AuthMethod.OAUTH2 : AccountConfig.AuthMethod.PASSWORD;
        config.setImapAuthMethod(auth);
        config.setSmtpAuthMethod(auth);

        // IMAP settings
        config.setImapHost((String) data.get("imap_host"));
        config.setImapPort(getInt(data, "imap_port", 993));
        config.setImapSsl(getBoolean(data, "imap_ssl", true));
        config.setImapUsername((String) data.getOrDefault("imap_username", config.getEmailAddress()));
        config.setImapPassword((String) data.get("imap_password"));

        // SMTP settings
        config.setSmtpHost((String) data.get("smtp_host"));
        config.setSmtpPort(getInt(data, "smtp_port", 465));
        config.setSmtpSsl(getBoolean(data, "smtp_ssl", true));
        config.setSmtpUsername((String) data.getOrDefault("smtp_username", config.getEmailAddress()));
        config.setSmtpPassword((String) data.get("smtp_password"));

        // OAuth tokens
        config.setOauthAccessToken((String) data.get("oauth_access_token"));
        config.setOauthRefreshToken((String) data.get("oauth_refresh_token"));
        String expiry = (String) data.get("oauth_token_expiry");
        if (expiry != null) {
            config.setOauthTokenExpiry(Instant.parse(expiry));
        }
        config.setOauthTokensInVault(getBoolean(data, "oauth_tokens_in_vault", false));

        // Permissions
        String permissions = (String) data.get("permissions");
        if (permissions != null) {
            config.setPermissions(parsePermissions(permissions));
        }

        // Status settings
        config.setIncludeInStatus(getBoolean(data, "include_in_status", false));
        Integer cacheTtl = (Integer) data.get("status_cache_ttl");
        config.setStatusCacheTtl(cacheTtl);

        // Signature settings
        config.setSignature((String) data.get("signature"));
        config.setIncludeFooter(getBoolean(data, "include_footer", true));
        config.setSignatureImagePath((String) data.get("signature_image_path"));

        // Default From address
        config.setDefaultFromAddress((String) data.get("default_from_address"));

        return config;
    }

    /**
     * Saves configuration to JSON file.
     */
    private static void saveToJson(Path file, AccountRegistry registry) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("default_account", registry.getDefaultAccountName());

        List<Map<String, Object>> accounts = new ArrayList<>();
        for (AccountConfig config : registry.getAccounts()) {
            accounts.add(accountToJson(config));
        }
        data.put("accounts", accounts);

        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
    }

    /**
     * Converts an account to JSON map.
     */
    private static Map<String, Object> accountToJson(AccountConfig config) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("account_name", config.getAccountName());
        data.put("email_address", config.getEmailAddress());
        data.put("full_name", config.getFullName());
        data.put("auth_method", config.getImapAuthMethod().name().toLowerCase());

        // IMAP settings
        data.put("imap_host", config.getImapHost());
        data.put("imap_port", config.getImapPort());
        data.put("imap_ssl", config.isImapSsl());
        data.put("imap_username", config.getImapUsername());
        if (config.getImapPassword() != null) {
            data.put("imap_password", config.getImapPassword());
        }

        // SMTP settings
        data.put("smtp_host", config.getSmtpHost());
        data.put("smtp_port", config.getSmtpPort());
        data.put("smtp_ssl", config.isSmtpSsl());
        data.put("smtp_username", config.getSmtpUsername());
        if (config.getSmtpPassword() != null) {
            data.put("smtp_password", config.getSmtpPassword());
        }

        // OAuth tokens. When tokens live in the vault, the access/refresh secrets are
        // NEVER written to the config file — only the flag and the (non-secret) expiry.
        if (!config.isOauthTokensInVault()) {
            if (config.getOauthAccessToken() != null) {
                data.put("oauth_access_token", config.getOauthAccessToken());
            }
            if (config.getOauthRefreshToken() != null) {
                data.put("oauth_refresh_token", config.getOauthRefreshToken());
            }
        }
        if (config.getOauthTokenExpiry() != null) {
            data.put("oauth_token_expiry", config.getOauthTokenExpiry().toString());
        }
        data.put("oauth_tokens_in_vault", config.isOauthTokensInVault());

        // Permissions
        data.put("permissions", formatPermissions(config.getPermissions()));

        // Status settings
        data.put("include_in_status", config.isIncludeInStatus());
        if (config.getStatusCacheTtl() != null) {
            data.put("status_cache_ttl", config.getStatusCacheTtl());
        }

        // Signature settings
        if (config.getSignature() != null) {
            data.put("signature", config.getSignature());
        }
        data.put("include_footer", config.isIncludeFooter());
        if (config.getSignatureImagePath() != null) {
            data.put("signature_image_path", config.getSignatureImagePath());
        }

        // Default From address
        if (config.getDefaultFromAddress() != null) {
            data.put("default_from_address", config.getDefaultFromAddress());
        }

        return data;
    }

    private static int getInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}
