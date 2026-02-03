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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gets the full content (including body) of one or more emails.
 *
 * @author César Obach
 */
public class GetEmailsContent extends BaseTool {

    public GetEmailsContent(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "get_emails_content";
    }

    @Override
    public String getDescription() {
        return "Get the full content (including body) of one or more emails by their email_id. " +
            "Use list_emails_metadata first to get the email_id. " +
            "This operation does NOT mark emails as read.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_ids", "array:string", "List of email_id to retrieve (obtained from list_emails_metadata)",
            "mailbox", "string", "The mailbox to retrieve emails from (default: INBOX, optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.READ)) {
            throw new IllegalArgumentException("Permission denied: READ not allowed for account '" + accountName + "'");
        }

        List<String> emailIds = getStringList(args, "email_ids");
        if (emailIds.isEmpty()) {
            throw new IllegalArgumentException("email_ids is required and cannot be empty");
        }

        String mailbox = getString(args, "mailbox", "INBOX");

        return context.emailClient(accountName).getEmailsContent(mailbox, emailIds);
    }
}
