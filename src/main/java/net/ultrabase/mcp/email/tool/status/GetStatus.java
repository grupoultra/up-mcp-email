/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gets status (unread/flagged counts) for all accounts configured for status reporting.
 *
 * @author César Obach
 */
public class GetStatus extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public GetStatus(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "get_status";
    }

    @Override
    public String getDescription() {
        return "Get status (unread/flagged counts) for all accounts configured for status reporting.";
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

            int totalUnread = 0;
            int totalFlagged = 0;

            for (AccountConfig config : context.accountRegistry().getAccounts()) {
                if (!config.isIncludeInStatus()) {
                    continue;
                }

                try {
                    var statusFuture = context.emailClient(config.getAccountName()).getStatus();
                    var status = statusFuture.join();

                    if (status.has("unread_count")) {
                        totalUnread += status.get("unread_count").asInt();
                    }
                    if (status.has("flagged_count")) {
                        totalFlagged += status.get("flagged_count").asInt();
                    }

                    accountsArray.add(status);
                } catch (Exception e) {
                    // Include error for this account
                    ObjectNode errorNode = objectMapper.createObjectNode();
                    errorNode.put("account_name", config.getAccountName());
                    errorNode.put("error", e.getMessage());
                    accountsArray.add(errorNode);
                }
            }

            result.put("total_unread", totalUnread);
            result.put("total_flagged", totalFlagged);

            return result;
        });
    }
}
