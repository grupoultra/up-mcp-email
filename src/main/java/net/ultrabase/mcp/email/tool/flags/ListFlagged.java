/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.flags;

import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lists flagged emails with their keywords.
 *
 * @author César Obach
 */
public class ListFlagged extends BaseTool {

    public ListFlagged(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "list_flagged";
    }

    @Override
    public String getDescription() {
        return "List flagged emails with their keywords. " +
            "Returns counts by keyword (e.g., Personal, Alta, HOLD) and email IDs.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "keyword", "string", "Filter by specific keyword (e.g., 'Personal', 'Alta'). If not specified, returns all flagged emails grouped by keyword. (optional)",
            "mailbox", "string", "The mailbox to search (default: INBOX, optional)"
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

        String keyword = getString(args, "keyword", null);
        String mailbox = getString(args, "mailbox", "INBOX");

        return context.emailClient(accountName).listFlagged(mailbox, keyword);
    }
}
