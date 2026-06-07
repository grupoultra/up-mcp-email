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
 * Downloads an email attachment and saves it to a specified path.
 *
 * @author César Obach
 */
public class DownloadAttachment extends BaseTool {

    public DownloadAttachment(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "download_attachment";
    }

    @Override
    public String getDescription() {
        return "Download an email attachment and save it to the specified path. " +
            "This feature must be explicitly enabled in settings (enable_attachment_download=true) " +
            "due to security considerations.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "email_id", "string", "The email ID (obtained from list_emails_metadata or get_emails_content)",
            "attachment_index", "integer", "0-based index of the attachment as shown by get_emails_content "
                + "(optional; preferred — robust to encoded/folded filenames)",
            "attachment_name", "string", "The attachment filename as shown in the attachments list "
                + "(optional; used when attachment_index is not provided)",
            "save_path", "string", "The absolute path where the attachment should be saved"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        String accountName = resolveAccount(args);

        // Check permission
        AccountConfig config = context.accountRegistry().getAccount(accountName);
        if (!config.hasPermission(AccountConfig.Permission.EXPORT)) {
            throw new IllegalArgumentException("Permission denied: EXPORT not allowed for account '" + accountName + "'");
        }

        String emailId = getString(args, "email_id");
        String savePath = getString(args, "save_path");
        String attachmentName = getString(args, "attachment_name", null);

        Integer attachmentIndex = null;
        Object idxRaw = args.get("attachment_index");
        if (idxRaw instanceof Number) {
            attachmentIndex = ((Number) idxRaw).intValue();
        } else if (idxRaw instanceof String && !((String) idxRaw).isBlank()) {
            try {
                attachmentIndex = Integer.parseInt(((String) idxRaw).trim());
            } catch (NumberFormatException ignored) {
                // fall through to name-based matching
            }
        }

        if (attachmentIndex == null && (attachmentName == null || attachmentName.isBlank())) {
            throw new IllegalArgumentException(
                "Provide either attachment_index (preferred) or attachment_name.");
        }

        return context.emailClient(accountName)
            .downloadAttachment(emailId, attachmentName, attachmentIndex, savePath);
    }
}
