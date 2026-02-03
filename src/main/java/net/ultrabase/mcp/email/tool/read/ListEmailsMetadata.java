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
 * Lists email metadata (without body content).
 * Returns email_id for use with get_emails_content.
 *
 * @author César Obach
 */
public class ListEmailsMetadata extends BaseTool {

    public ListEmailsMetadata(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "list_emails_metadata";
    }

    @Override
    public String getDescription() {
        return "List email metadata (email_id, subject, sender, recipients, date) without body content. " +
            "Returns email_id for use with get_emails_content.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "mailbox", "string", "The mailbox to retrieve emails from (default: INBOX, optional)",
            "page", "integer", "The page number to retrieve, starting from 1 (default: 1, optional)",
            "page_size", "integer", "The number of emails to retrieve per page (default: 10, optional)",
            "order", "string", "Order emails by date: 'asc' or 'desc' (default: desc, optional)",
            "subject", "string", "Filter emails by subject (optional)",
            "from_address", "string", "Filter emails by sender address (optional)",
            "to_address", "string", "Filter emails by recipient address (optional)",
            "since", "string", "Retrieve emails since this datetime (UTC, ISO format, optional)",
            "before", "string", "Retrieve emails before this datetime (UTC, ISO format, optional)"
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

        String mailbox = getString(args, "mailbox", "INBOX");
        int page = getInt(args, "page", 1);
        int pageSize = getInt(args, "page_size", 10);
        String order = getString(args, "order", "desc");
        String subject = getString(args, "subject", null);
        String fromAddress = getString(args, "from_address", null);
        String toAddress = getString(args, "to_address", null);
        String since = getString(args, "since", null);
        String before = getString(args, "before", null);

        return context.emailClient(accountName).listEmailsMetadata(
            mailbox, page, pageSize, order, subject, fromAddress, toAddress, since, before
        );
    }
}
