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
 * Lists all available mailbox folders.
 *
 * @author César Obach
 */
public class ListFolders extends BaseTool {

    public ListFolders(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "list_folders";
    }

    @Override
    public String getDescription() {
        return "List all available mailbox folders (INBOX, Sent, Drafts, etc.).";
    }

    @Override
    public String getInputSchema() {
        return schemaEmpty();
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.LIST)) {
            throw new IllegalArgumentException("Permission denied: LIST not allowed for account '" + accountName + "'");
        }

        return context.emailClient(accountName).listFolders();
    }
}
