/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.write;

import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Marks one or more emails as read.
 *
 * @author César Obach
 */
public class MarkAsRead extends BaseTool {

    public MarkAsRead(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "mark_as_read";
    }

    @Override
    public String getDescription() {
        return "Mark one or more emails as read without fetching their content.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_ids", "array:string", "List of email_id to mark as read (obtained from check_unread or list_emails_metadata)",
            "mailbox", "string", "The mailbox containing the emails (default: INBOX, optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.UPDATE)) {
            throw new IllegalArgumentException("Permission denied: UPDATE not allowed for account '" + accountName + "'");
        }

        List<String> emailIds = getStringList(args, "email_ids");
        if (emailIds.isEmpty()) {
            throw new IllegalArgumentException("email_ids is required and cannot be empty");
        }

        String mailbox = getString(args, "mailbox", "INBOX");

        return context.emailClient(accountName).markAsRead(mailbox, emailIds);
    }
}
