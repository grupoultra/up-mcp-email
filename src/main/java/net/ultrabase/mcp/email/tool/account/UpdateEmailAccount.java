/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Updates an existing email account's password and/or display name.
 *
 * @author César Obach
 */
public class UpdateEmailAccount extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public UpdateEmailAccount(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "update_email_account";
    }

    @Override
    public String getDescription() {
        return "Update an existing email account's password, display name, signature, signature image, " +
            "footer setting, and/or account alias. At least one field must be provided.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "password", "string", "New password (App Password for Gmail). Updates both IMAP and SMTP. (optional)",
            "full_name", "string", "New display name for the account. (optional)",
            "new_account_name", "string", "New account alias/name. Use to rename the account identifier. (optional)",
            "signature", "string", "Personal signature for emails (optional, set empty string to remove)",
            "include_footer", "boolean", "Include 'Sent vía ultraPRO' footer (optional)",
            "signature_image", "string", "Absolute path to signature logo image (optional, set empty to remove)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);
            String password = getString(args, "password", null);
            String fullName = getString(args, "full_name", null);
            String newAccountName = getString(args, "new_account_name", null);

            // For signature, we need to distinguish between not provided vs empty string
            boolean hasSignature = args.containsKey("signature");
            String signature = hasSignature ? getString(args, "signature", "") : null;

            // For include_footer, null means not provided
            Boolean includeFooter = args.containsKey("include_footer")
                ? getBoolean(args, "include_footer", true)
                : null;
            boolean hasIncludeFooter = includeFooter != null;

            // For signature_image, we need to distinguish between not provided vs empty string
            boolean hasSignatureImage = args.containsKey("signature_image");
            String signatureImagePath = hasSignatureImage ? getString(args, "signature_image", "") : null;

            // Validate signature image exists if provided (and not empty)
            if (hasSignatureImage && !signatureImagePath.isEmpty()) {
                Path imgPath = Path.of(signatureImagePath);
                if (!Files.exists(imgPath)) {
                    throw new IllegalArgumentException("Signature image not found: " + signatureImagePath);
                }
                if (!Files.isReadable(imgPath)) {
                    throw new IllegalArgumentException("Signature image not readable: " + signatureImagePath);
                }
            }

            boolean hasPassword = password != null && !password.isEmpty();
            boolean hasFullName = fullName != null && !fullName.isEmpty();
            boolean hasNewName = newAccountName != null && !newAccountName.isEmpty();

            if (!hasPassword && !hasFullName && !hasNewName && !hasSignature
                    && !hasIncludeFooter && !hasSignatureImage) {
                throw new IllegalArgumentException(
                    "At least one of 'password', 'full_name', 'new_account_name', 'signature', " +
                    "'include_footer', or 'signature_image' must be provided");
            }

            List<String> updatedFields = new ArrayList<>();
            String finalAccountName = accountName;

            // Handle rename first (before other updates)
            if (hasNewName) {
                if (!context.accountRegistry().renameAccount(accountName, newAccountName)) {
                    throw new IllegalArgumentException(
                        "Unable to rename account: either '" + accountName + "' not found or '" +
                        newAccountName + "' already exists");
                }
                updatedFields.add("account_name='" + newAccountName + "'");
                finalAccountName = newAccountName;
            }

            // Handle password and fullName updates (using the possibly renamed account)
            if (hasPassword || hasFullName) {
                if (!context.accountRegistry().updateAccount(finalAccountName, password, fullName)) {
                    throw new IllegalArgumentException("Account '" + finalAccountName + "' not found");
                }

                if (hasPassword) {
                    updatedFields.add("password");
                }
                if (hasFullName) {
                    updatedFields.add("full_name='" + fullName + "'");
                }
            }

            // Handle signature, footer, and signature image updates
            if (hasSignature || hasIncludeFooter || hasSignatureImage) {
                if (!context.accountRegistry().updateAccountSignature(
                        finalAccountName,
                        hasSignature ? signature : null,
                        includeFooter,
                        hasSignatureImage ? signatureImagePath : null)) {
                    throw new IllegalArgumentException("Account '" + finalAccountName + "' not found");
                }

                if (hasSignature) {
                    if (signature.isEmpty()) {
                        updatedFields.add("signature removed");
                    } else {
                        String truncated = signature.length() > 30
                            ? signature.substring(0, 30) + "..."
                            : signature;
                        // Replace newlines for display
                        truncated = truncated.replace("\n", "\\n");
                        updatedFields.add("signature='" + truncated + "'");
                    }
                }
                if (hasIncludeFooter) {
                    updatedFields.add("include_footer=" + includeFooter);
                }
                if (hasSignatureImage) {
                    if (signatureImagePath.isEmpty()) {
                        updatedFields.add("signature_image removed");
                    } else {
                        updatedFields.add("signature_image='" + signatureImagePath + "'");
                    }
                }
            }

            context.accountRegistry().save();

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("account_name", finalAccountName);
            result.put("updated_fields", String.join(", ", updatedFields));
            result.put("message", String.format(
                "Account '%s' updated: %s",
                finalAccountName,
                String.join(", ", updatedFields)
            ));

            return result;
        });
    }
}
