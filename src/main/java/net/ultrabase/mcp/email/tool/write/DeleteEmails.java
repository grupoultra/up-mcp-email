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
 * Deletes one or more emails.
 *
 * @author César Obach
 */
public class DeleteEmails extends BaseTool {

    public DeleteEmails(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "delete_emails";
    }

    @Override
    public String getDescription() {
        return "Delete one or more emails by their email_id. " +
            "Use list_emails_metadata first to get the email_id.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_ids", "array:string", "List of email_id to delete (obtained from list_emails_metadata)",
            "mailbox", "string", "The mailbox to delete emails from (default: INBOX, optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.DELETE)) {
            throw new IllegalArgumentException("Permission denied: DELETE not allowed for account '" + accountName + "'");
        }

        List<String> emailIds = getStringList(args, "email_ids");
        if (emailIds.isEmpty()) {
            throw new IllegalArgumentException("email_ids is required and cannot be empty");
        }

        String mailbox = getString(args, "mailbox", "INBOX");

        return context.emailClient(accountName).deleteEmails(mailbox, emailIds);
    }
}
