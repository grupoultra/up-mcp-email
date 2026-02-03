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
 * Marks one or more emails as unread.
 *
 * @author César Obach
 */
public class MarkAsUnread extends BaseTool {

    public MarkAsUnread(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "mark_as_unread";
    }

    @Override
    public String getDescription() {
        return "Mark one or more emails as unread.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_ids", "array:string", "List of email_id to mark as unread (obtained from list_emails_metadata)",
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

        return context.emailClient(accountName).markAsUnread(mailbox, emailIds);
    }
}
