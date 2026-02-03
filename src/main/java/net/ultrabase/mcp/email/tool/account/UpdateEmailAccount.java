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
        return "Update an existing email account's password and/or display name. " +
            "At least one of password or full_name must be provided.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "password", "string", "New password (App Password for Gmail). Updates both IMAP and SMTP. (optional)",
            "full_name", "string", "New display name for the account. (optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);
            String password = getString(args, "password", null);
            String fullName = getString(args, "full_name", null);

            if ((password == null || password.isEmpty()) && (fullName == null || fullName.isEmpty())) {
                throw new IllegalArgumentException("At least one of 'password' or 'full_name' must be provided");
            }

            if (!context.accountRegistry().updateAccount(accountName, password, fullName)) {
                throw new IllegalArgumentException("Account '" + accountName + "' not found");
            }

            context.accountRegistry().save();

            List<String> updatedFields = new ArrayList<>();
            if (password != null && !password.isEmpty()) {
                updatedFields.add("password");
            }
            if (fullName != null && !fullName.isEmpty()) {
                updatedFields.add("full_name='" + fullName + "'");
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("account_name", accountName);
            result.put("updated_fields", String.join(", ", updatedFields));
            result.put("message", String.format(
                "Account '%s' updated: %s",
                accountName,
                String.join(", ", updatedFields)
            ));

            return result;
        });
    }
}
