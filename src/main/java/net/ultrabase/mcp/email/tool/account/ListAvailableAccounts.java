/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.config.ConfigLoader;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lists all configured email accounts with masked credentials.
 *
 * @author César Obach
 */
public class ListAvailableAccounts extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public ListAvailableAccounts(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "list_available_accounts";
    }

    @Override
    public String getDescription() {
        return "List all configured email accounts with masked credentials.";
    }

    @Override
    public String getInputSchema() {
        // No parameters needed - doesn't even need account_name
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode accountsArray = result.putArray("accounts");

            for (AccountConfig config : context.accountRegistry().getAccounts()) {
                AccountConfig masked = config.masked();
                ObjectNode accountNode = objectMapper.createObjectNode();

                accountNode.put("account_name", masked.getAccountName());
                accountNode.put("email_address", masked.getEmailAddress());
                accountNode.put("full_name", masked.getFullName());
                accountNode.put("auth_method", masked.getImapAuthMethod().name().toLowerCase());
                accountNode.put("imap_host", masked.getImapHost());
                accountNode.put("smtp_host", masked.getSmtpHost());
                accountNode.put("is_default", masked.isDefault());
                accountNode.put("permissions", ConfigLoader.formatPermissions(masked.getPermissions()));
                accountNode.put("include_in_status", masked.isIncludeInStatus());
                if (masked.isOauthReauthRequired()) {
                    // Surface terminal OAuth failure loudly: this account cannot operate until
                    // reauthorize_email_account is run.
                    accountNode.put("reauth_required", true);
                    accountNode.put("reauth_since", String.valueOf(masked.getOauthReauthSince()));
                }

                accountsArray.add(accountNode);
            }

            String defaultAccount = context.accountRegistry().getDefaultAccountName();
            result.put("default_account", defaultAccount != null ? defaultAccount : "");
            result.put("count", accountsArray.size());

            return result;
        });
    }
}
