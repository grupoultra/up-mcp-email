/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Updates status reporting settings for an account.
 *
 * @author César Obach
 */
public class UpdateStatusSettings extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public UpdateStatusSettings(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "update_status_settings";
    }

    @Override
    public String getDescription() {
        return "Update status reporting settings for an account (include_in_status, cache TTL).";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "include_in_status", "boolean", "Whether to include this account in status responses (optional)",
            "status_cache_ttl", "integer", "Cache TTL in seconds. Set to enable caching. (optional)",
            "disable_cache", "boolean", "Set to true to disable caching (always query fresh) (default: false, optional)"
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);

            Boolean includeInStatus = null;
            Object includeVal = args.get("include_in_status");
            if (includeVal != null) {
                includeInStatus = includeVal instanceof Boolean ? (Boolean) includeVal
                    : Boolean.parseBoolean(includeVal.toString());
            }

            Integer statusCacheTtl = null;
            Object ttlVal = args.get("status_cache_ttl");
            if (ttlVal != null) {
                statusCacheTtl = ttlVal instanceof Number ? ((Number) ttlVal).intValue()
                    : Integer.parseInt(ttlVal.toString());
            }

            boolean disableCache = getBoolean(args, "disable_cache", false);

            if (includeInStatus == null && statusCacheTtl == null && !disableCache) {
                throw new IllegalArgumentException("At least one setting must be provided");
            }

            if (!context.accountRegistry().updateStatusSettings(
                    accountName, includeInStatus, statusCacheTtl, disableCache)) {
                throw new IllegalArgumentException("Account '" + accountName + "' not found");
            }

            context.accountRegistry().save();

            List<String> changes = new ArrayList<>();
            if (includeInStatus != null) {
                changes.add("include_in_status=" + includeInStatus);
            }
            if (disableCache) {
                changes.add("cache=disabled");
            } else if (statusCacheTtl != null) {
                changes.add("cache_ttl=" + statusCacheTtl + "s");
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("account_name", accountName);
            result.put("changes", String.join(", ", changes));
            result.put("message", String.format(
                "Updated status settings for '%s': %s",
                accountName, String.join(", ", changes)
            ));

            return result;
        });
    }
}
