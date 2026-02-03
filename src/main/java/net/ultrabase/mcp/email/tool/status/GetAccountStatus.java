/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.status;

import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gets status for a specific account.
 *
 * @author César Obach
 */
public class GetAccountStatus extends BaseTool {

    public GetAccountStatus(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "get_account_status_tool";
    }

    @Override
    public String getDescription() {
        return "Get status for a specific account.";
    }

    @Override
    public String getInputSchema() {
        return schemaEmpty();
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);
        return context.emailClient(accountName).getStatus();
    }
}
