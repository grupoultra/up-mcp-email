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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Deletes an email account from the configuration.
 *
 * @author César Obach
 */
public class DeleteEmailAccount extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public DeleteEmailAccount(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "delete_email_account";
    }

    @Override
    public String getDescription() {
        return "Delete an email account from the configuration. " +
            "This removes the account and its stored credentials. Cannot delete the last remaining account.";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
            "\"account_name\":{\"type\":\"string\",\"description\":\"Account name to delete\"}" +
            "},\"required\":[\"account_name\"]}";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = getString(args, "account_name");

            // Prevent deleting the last account
            if (context.accountRegistry().getAccountNames().size() <= 1) {
                throw new IllegalArgumentException("Cannot delete the last remaining account");
            }

            // Check if trying to delete default account
            String defaultAccount = context.accountRegistry().getDefaultAccountName();
            boolean wasDefault = accountName.equals(defaultAccount);

            if (!context.accountRegistry().removeAccount(accountName)) {
                throw new IllegalArgumentException("Account '" + accountName + "' not found");
            }

            context.accountRegistry().save();

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("deleted_account", accountName);

            if (wasDefault) {
                String newDefault = context.accountRegistry().getDefaultAccountName();
                result.put("new_default_account", newDefault);
                result.put("message", String.format(
                    "Account '%s' deleted. New default account: '%s'", accountName, newDefault));
            } else {
                result.put("message", String.format("Account '%s' deleted", accountName));
            }

            return result;
        });
    }
}
