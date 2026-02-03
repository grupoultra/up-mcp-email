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
        return "Update an existing email account's password, display name, and/or account alias. " +
            "At least one of password, full_name, or new_account_name must be provided.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "password", "string", "New password (App Password for Gmail). Updates both IMAP and SMTP. (optional)",
            "full_name", "string", "New display name for the account. (optional)",
            "new_account_name", "string", "New account alias/name. Use to rename the account identifier. (optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);
            String password = getString(args, "password", null);
            String fullName = getString(args, "full_name", null);
            String newAccountName = getString(args, "new_account_name", null);

            boolean hasPassword = password != null && !password.isEmpty();
            boolean hasFullName = fullName != null && !fullName.isEmpty();
            boolean hasNewName = newAccountName != null && !newAccountName.isEmpty();

            if (!hasPassword && !hasFullName && !hasNewName) {
                throw new IllegalArgumentException(
                    "At least one of 'password', 'full_name', or 'new_account_name' must be provided");
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
