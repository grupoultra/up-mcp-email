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
 * Sends an email using the specified account.
 *
 * @author César Obach
 */
public class SendEmail extends BaseTool {

    public SendEmail(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "send_email";
    }

    @Override
    public String getDescription() {
        return "Send an email using the specified account. " +
            "Body format (Markdown, HTML, or plain text) is automatically detected. " +
            "Supports replying to emails with proper threading when in_reply_to is provided.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "recipients", "array:string", "A list of recipient email addresses",
            "subject", "string", "The subject of the email",
            "body", "string", "The body of the email. Supports Markdown, HTML, or plain text - format is auto-detected.",
            "cc", "array:string", "A list of CC email addresses (optional)",
            "bcc", "array:string", "A list of BCC email addresses (optional)",
            "attachments", "array:string", "A list of absolute file paths to attach to the email (optional)",
            "in_reply_to", "string", "Message-ID of the email being replied to. Enables proper threading. (optional)",
            "references", "string", "Space-separated Message-IDs for the thread chain. Usually includes in_reply_to plus ancestors. (optional)",
            "include_signature", "boolean", "Include account signature in email (default: true)",
            "from_address", "string", "Override From address. Use when sending from an alias authorized on the same SMTP server. Authentication uses account credentials. (optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.EXECUTE)) {
            throw new IllegalArgumentException("Permission denied: EXECUTE (send) not allowed for account '" + accountName + "'");
        }

        List<String> recipients = getStringList(args, "recipients");
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients is required and cannot be empty");
        }

        String subject = getString(args, "subject");
        String body = getString(args, "body");
        List<String> cc = getStringList(args, "cc");
        List<String> bcc = getStringList(args, "bcc");
        List<String> attachments = getStringList(args, "attachments");
        String inReplyTo = getString(args, "in_reply_to", null);
        String references = getString(args, "references", null);
        boolean includeSignature = getBoolean(args, "include_signature", true);
        String fromAddress = getString(args, "from_address", null);

        return context.emailClient(accountName).sendEmail(
            recipients, subject, body,
            cc.isEmpty() ? null : cc,
            bcc.isEmpty() ? null : bcc,
            attachments.isEmpty() ? null : attachments,
            inReplyTo, references, includeSignature,
            fromAddress
        );
    }
}
