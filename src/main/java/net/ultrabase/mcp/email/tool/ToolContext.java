/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool;

import net.ultrabase.mcp.email.client.IEmailClient;
import net.ultrabase.mcp.email.config.AccountRegistry;

/**
 * Dependency container for tool handlers.
 * Provides all shared dependencies (clients, registry) to tools via constructor injection.
 *
 * Supports multi-account: tools can request clients for specific accounts.
 *
 * Uses interfaces instead of concrete classes to enable easy mocking in tests
 * without bytecode manipulation (Java 25+ compatible).
 *
 * @author César Obach
 */
public record ToolContext(
    AccountRegistry accountRegistry
) {
    /**
     * Gets the email client for a specific account.
     *
     * @param accountName Account name (null uses default)
     * @return Email client for the account
     */
    public IEmailClient emailClient(String accountName) {
        return accountRegistry.getEmailClient(accountName);
    }

    /**
     * Gets the email client for the default account.
     */
    public IEmailClient emailClient() {
        return emailClient(null);
    }
}
