/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.config;

import net.ultrabase.mcp.email.client.EmailClient;
import net.ultrabase.mcp.email.client.IEmailClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of configured email accounts.
 * Manages multi-account configuration and provides email clients for each account.
 *
 * @author César Obach
 */
public class AccountRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AccountRegistry.class);

    private final Map<String, AccountConfig> accounts = new LinkedHashMap<>();
    private final Map<String, IEmailClient> clientCache = new ConcurrentHashMap<>();
    private String defaultAccountName;

    /**
     * Creates an empty registry.
     */
    public AccountRegistry() {
    }

    /**
     * Loads account configuration from disk.
     *
     * @return Loaded registry
     */
    public static AccountRegistry load() {
        return ConfigLoader.load();
    }

    /**
     * Adds an account to the registry.
     *
     * @param config Account configuration
     */
    public void addAccount(AccountConfig config) {
        String name = config.getAccountName();
        accounts.put(name, config);

        // Set as default if first account or explicitly marked
        if (defaultAccountName == null || config.isDefault()) {
            defaultAccountName = name;
        }

        // Invalidate cached client
        clientCache.remove(name);

        logger.info("Added account: {}", name);
    }

    /**
     * Removes an account from the registry.
     *
     * @param accountName Account name to remove
     * @return true if removed, false if not found
     */
    public boolean removeAccount(String accountName) {
        AccountConfig removed = accounts.remove(accountName);
        if (removed != null) {
            clientCache.remove(accountName);

            // Update default if needed
            if (accountName.equals(defaultAccountName)) {
                defaultAccountName = accounts.isEmpty() ? null : accounts.keySet().iterator().next();
            }

            logger.info("Removed account: {}", accountName);
            return true;
        }
        return false;
    }

    /**
     * Gets an account configuration by name.
     *
     * @param accountName Account name (null for default)
     * @return Account configuration
     * @throws IllegalArgumentException if account not found
     */
    public AccountConfig getAccount(String accountName) {
        String name = accountName != null ? accountName : defaultAccountName;
        if (name == null) {
            throw new IllegalArgumentException("No email accounts configured");
        }

        AccountConfig config = accounts.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Account not found: " + name);
        }
        return config;
    }

    /**
     * Gets an account configuration by name, or null if not found.
     *
     * @param accountName Account name
     * @return Account configuration or null
     */
    public AccountConfig getAccountOrNull(String accountName) {
        return accounts.get(accountName);
    }

    /**
     * Gets the email client for an account.
     *
     * @param accountName Account name (null for default)
     * @return Email client
     */
    public IEmailClient getEmailClient(String accountName) {
        String name = accountName != null ? accountName : defaultAccountName;
        if (name == null) {
            throw new IllegalArgumentException("No email accounts configured");
        }

        return clientCache.computeIfAbsent(name, n -> {
            AccountConfig config = getAccount(n);
            // Pass save callback so refreshed tokens are persisted
            return new EmailClient(config, this::save);
        });
    }

    /**
     * Gets the names of all configured accounts.
     */
    public Set<String> getAccountNames() {
        return Collections.unmodifiableSet(accounts.keySet());
    }

    /**
     * Gets all account configurations.
     */
    public Collection<AccountConfig> getAccounts() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    /**
     * Gets the default account name.
     */
    public String getDefaultAccountName() {
        return defaultAccountName;
    }

    /**
     * Sets the default account.
     *
     * @param accountName Account name to set as default
     * @return true if set, false if account not found
     */
    public boolean setDefaultAccount(String accountName) {
        if (!accounts.containsKey(accountName)) {
            return false;
        }
        defaultAccountName = accountName;
        return true;
    }

    /**
     * Returns true if any accounts are configured.
     */
    public boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    /**
     * Saves the registry to disk.
     */
    public void save() {
        ConfigLoader.save(this);
    }

    /**
     * Updates an account's password and/or full name.
     *
     * @param accountName Account name
     * @param password    New password (null to skip)
     * @param fullName    New full name (null to skip)
     * @return true if updated, false if account not found
     */
    public boolean updateAccount(String accountName, String password, String fullName) {
        AccountConfig config = accounts.get(accountName);
        if (config == null) {
            return false;
        }

        if (password != null) {
            config.setImapPassword(password);
            config.setSmtpPassword(password);
            // Invalidate cached client
            clientCache.remove(accountName);
        }

        if (fullName != null) {
            config.setFullName(fullName);
        }

        return true;
    }

    /**
     * Updates an account's permissions.
     *
     * @param accountName Account name
     * @param permissions New permissions set
     * @return true if updated, false if account not found
     */
    public boolean updatePermissions(String accountName, Set<AccountConfig.Permission> permissions) {
        AccountConfig config = accounts.get(accountName);
        if (config == null) {
            return false;
        }
        config.setPermissions(permissions);
        return true;
    }

    /**
     * Updates an account's status reporting settings.
     *
     * @param accountName     Account name
     * @param includeInStatus Whether to include in status responses
     * @param statusCacheTtl  Cache TTL in seconds (null to use default)
     * @param clearCacheTtl   If true, disables caching
     * @return true if updated, false if account not found
     */
    public boolean updateStatusSettings(String accountName, Boolean includeInStatus,
                                         Integer statusCacheTtl, boolean clearCacheTtl) {
        AccountConfig config = accounts.get(accountName);
        if (config == null) {
            return false;
        }

        if (includeInStatus != null) {
            config.setIncludeInStatus(includeInStatus);
        }

        if (clearCacheTtl) {
            config.setStatusCacheTtl(null);
        } else if (statusCacheTtl != null) {
            config.setStatusCacheTtl(statusCacheTtl);
        }

        return true;
    }

    /**
     * Updates an account's signature, footer, and signature image settings.
     *
     * @param accountName        Account name
     * @param signature          New signature (null to skip, empty string to remove)
     * @param includeFooter      Whether to include ultraPRO footer (null to skip)
     * @param signatureImagePath Path to signature image (null to skip, empty string to remove)
     * @return true if updated, false if account not found
     */
    public boolean updateAccountSignature(String accountName, String signature,
                                           Boolean includeFooter, String signatureImagePath) {
        AccountConfig config = accounts.get(accountName);
        if (config == null) {
            return false;
        }

        if (signature != null) {
            config.setSignature(signature.isEmpty() ? null : signature);
        }

        if (includeFooter != null) {
            config.setIncludeFooter(includeFooter);
        }

        if (signatureImagePath != null) {
            config.setSignatureImagePath(signatureImagePath.isEmpty() ? null : signatureImagePath);
        }

        return true;
    }

    /**
     * Renames an account (changes its alias).
     * Updates the map key, the config's accountName field, default account reference if applicable,
     * and invalidates the cached client.
     *
     * @param oldAccountName Current account name
     * @param newAccountName New account name
     * @return true if renamed, false if old account not found or new name already exists
     */
    public boolean renameAccount(String oldAccountName, String newAccountName) {
        // Validate old account exists
        AccountConfig config = accounts.get(oldAccountName);
        if (config == null) {
            logger.warn("Cannot rename: account '{}' not found", oldAccountName);
            return false;
        }

        // Validate new name doesn't already exist
        if (accounts.containsKey(newAccountName)) {
            logger.warn("Cannot rename: account '{}' already exists", newAccountName);
            return false;
        }

        // Update the config's internal account name
        config.setAccountName(newAccountName);

        // Re-key in the map
        accounts.remove(oldAccountName);
        accounts.put(newAccountName, config);

        // Update default if this was the default account
        if (oldAccountName.equals(defaultAccountName)) {
            defaultAccountName = newAccountName;
        }

        // Invalidate cached client (will be recreated with new name on next use)
        clientCache.remove(oldAccountName);

        logger.info("Renamed account '{}' to '{}'", oldAccountName, newAccountName);
        return true;
    }
}
