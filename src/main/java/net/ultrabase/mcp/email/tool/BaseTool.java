/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for all MCP tool handlers.
 * Provides common functionality for argument extraction and schema building.
 *
 * Each tool implementation must provide:
 * - getName(): Tool identifier (e.g., "list_emails_metadata")
 * - getDescription(): User-facing description
 * - getInputSchema(): JSON schema for parameters
 * - execute(): Tool logic
 *
 * @author César Obach
 */
public abstract class BaseTool {

    protected final ToolContext context;

    public BaseTool(ToolContext context) {
        this.context = context;
    }

    /**
     * Gets the unique tool name (e.g., "list_emails_metadata").
     */
    public abstract String getName();

    /**
     * Gets the tool description shown to users.
     */
    public abstract String getDescription();

    /**
     * Gets the JSON schema for tool parameters.
     * Use the schema() helper to build this.
     */
    public abstract String getInputSchema();

    /**
     * Executes the tool with the given arguments.
     *
     * @param args Tool arguments as a map
     * @return CompletableFuture with the result (JsonNode or other serializable type)
     */
    public abstract CompletableFuture<?> execute(Map<String, Object> args);

    // ==================== ARGUMENT EXTRACTION HELPERS ====================

    /**
     * Extracts a required string parameter.
     */
    protected String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    /**
     * Extracts an optional string parameter with default value.
     */
    protected String getString(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Extracts an integer parameter with default value.
     */
    protected int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Extracts a boolean parameter with default value.
     */
    protected boolean getBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * Extracts a map parameter (returns empty map if missing).
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getMap(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * Extracts a list of strings (returns empty list if missing).
     */
    @SuppressWarnings("unchecked")
    protected List<String> getStringList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * Extracts a list of maps (returns empty list if missing).
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> getList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    // ==================== ACCOUNT HELPER ====================

    /**
     * Account parameter name used in all tools.
     */
    protected static final String ACCOUNT_PARAM = "account_name";

    /**
     * Account parameter description.
     */
    protected static final String ACCOUNT_DESC = "Account name (optional, uses default if not specified)";

    /**
     * Extracts the account name from tool arguments.
     *
     * @param args Tool arguments
     * @return Account name, or null if not specified (will use default)
     */
    protected String getAccount(Map<String, Object> args) {
        Object value = args.get(ACCOUNT_PARAM);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    /**
     * Resolves account name to actual account, using default if null.
     */
    protected String resolveAccount(Map<String, Object> args) {
        String accountName = getAccount(args);
        if (accountName != null) {
            return accountName;
        }
        String defaultAccount = context.accountRegistry().getDefaultAccountName();
        if (defaultAccount == null) {
            throw new IllegalArgumentException("No email accounts configured. Use add_email_account to add one.");
        }
        return defaultAccount;
    }

    // ==================== SCHEMA BUILDING HELPER ====================

    /**
     * Builds a JSON schema string for tool parameters.
     * Automatically appends the "account_name" parameter for multi-account support.
     *
     * Usage:
     * schema("name", "type", "description", "name2", "type2", "description2", ...)
     *
     * Parameters marked with "optional" in description are not required.
     *
     * Example:
     * schema("page", "integer", "Page number (default: 1, optional)")
     * → {"type":"object","properties":{"page":{"type":"integer","description":"Page number..."},"account_name":{"type":"string","description":"Account name..."}}}
     *
     * @param args Triplets of (propertyName, type, description)
     * @return JSON schema string with account_name parameter auto-injected
     */
    protected String schema(String... args) {
        if (args.length % 3 != 0) {
            throw new IllegalArgumentException("Schema args must be triplets of (name, type, description)");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"object\",\"properties\":{");

        List<String> required = new ArrayList<>();
        boolean first = true;

        // Add user-defined properties
        for (int i = 0; i < args.length; i += 3) {
            String propName = args[i];
            String propType = args[i + 1];
            String propDesc = args[i + 2];

            if (!first) sb.append(",");
            first = false;

            sb.append("\"").append(propName).append("\":{");

            // Handle array type with items
            if (propType.startsWith("array:")) {
                String itemType = propType.substring(6);
                sb.append("\"type\":\"array\",");
                sb.append("\"items\":{\"type\":\"").append(itemType).append("\"},");
            } else {
                sb.append("\"type\":\"").append(propType).append("\",");
            }

            sb.append("\"description\":\"").append(escapeJson(propDesc)).append("\"");
            sb.append("}");

            // If description doesn't contain "optional", mark as required
            if (!propDesc.toLowerCase().contains("optional") && !propDesc.toLowerCase().contains("default")) {
                required.add(propName);
            }
        }

        // Auto-inject account_name parameter (always optional)
        if (!first) sb.append(",");
        sb.append("\"").append(ACCOUNT_PARAM).append("\":{");
        sb.append("\"type\":\"string\",");
        sb.append("\"description\":\"").append(escapeJson(ACCOUNT_DESC)).append("\"");
        sb.append("}");

        sb.append("}");

        // Add required array if there are required properties
        if (!required.isEmpty()) {
            sb.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(required.get(i)).append("\"");
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a schema with no custom parameters (only account_name).
     */
    protected String schemaEmpty() {
        return schema();
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
