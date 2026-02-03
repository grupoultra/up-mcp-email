/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.read;

import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Checks for unread emails.
 *
 * @author César Obach
 */
public class CheckUnread extends BaseTool {

    public CheckUnread(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "check_unread";
    }

    @Override
    public String getDescription() {
        return "Check for unread emails. Returns count of unread and total emails, " +
            "plus IDs and sizes of most recent unread.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "max_ids", "integer", "Maximum number of unread email IDs to return per category (default: 20, optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.LIST)) {
            throw new IllegalArgumentException("Permission denied: LIST not allowed for account '" + accountName + "'");
        }

        int maxIds = getInt(args, "max_ids", 20);

        return context.emailClient(accountName).checkUnread(maxIds);
    }
}
