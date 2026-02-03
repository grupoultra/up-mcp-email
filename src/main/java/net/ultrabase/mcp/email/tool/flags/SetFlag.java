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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adds a flag or keyword to one or more emails.
 *
 * @author César Obach
 */
public class SetFlag extends BaseTool {

    public SetFlag(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "set_flag";
    }

    @Override
    public String getDescription() {
        return "Add a flag or keyword to one or more emails. " +
            "Use '\\\\Flagged' for the standard flag, or custom keywords like 'Personal', 'Alta', 'HOLD'.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_ids", "array:string", "List of email_id to flag (obtained from list_emails_metadata)",
            "flags", "array:string", "Flags to add. Use '\\\\Flagged' for standard flag, or keywords like 'Personal', 'Alta'.",
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

        List<String> flags = getStringList(args, "flags");
        if (flags.isEmpty()) {
            throw new IllegalArgumentException("flags is required and cannot be empty");
        }

        String mailbox = getString(args, "mailbox", "INBOX");

        return context.emailClient(accountName).setFlags(mailbox, emailIds, flags);
    }
}
