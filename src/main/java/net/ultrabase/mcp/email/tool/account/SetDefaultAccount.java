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
 * Sets the default email account.
 *
 * @author César Obach
 */
public class SetDefaultAccount extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public SetDefaultAccount(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "set_default_account";
    }

    @Override
    public String getDescription() {
        return "Set the default email account.";
    }

    @Override
    public String getInputSchema() {
        // Note: This tool uses "account_name" but it's required, not optional
        return "{\"type\":\"object\",\"properties\":{" +
            "\"account_name\":{\"type\":\"string\",\"description\":\"Account name to set as default\"}" +
            "},\"required\":[\"account_name\"]}";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = getString(args, "account_name");

            if (!context.accountRegistry().setDefaultAccount(accountName)) {
                throw new IllegalArgumentException("Account '" + accountName + "' not found");
            }

            context.accountRegistry().save();

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("default_account", accountName);
            result.put("message", String.format("Default account set to '%s'", accountName));

            return result;
        });
    }
}
