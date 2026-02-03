/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.ultrabase.mcp.email.config.AccountRegistry;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;
import net.ultrabase.mcp.email.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server for Email (IMAP/SMTP).
 * Provides 20 tools for email operations with OAuth2 and password auth support.
 *
 * @author César Obach
 */
public class EmailServer {

    private static final Logger logger = LoggerFactory.getLogger(EmailServer.class);

    private final AccountRegistry accountRegistry;
    private final McpJsonMapper jsonMapper;
    private final ObjectMapper objectMapper;
    private final List<SyncToolSpecification> toolRegistrations = new ArrayList<>();

    /**
     * Creates an EmailServer with multi-account support.
     *
     * @param accountRegistry Registry of configured email accounts
     * @param jsonMapper      JSON mapper for MCP protocol
     */
    public EmailServer(AccountRegistry accountRegistry, McpJsonMapper jsonMapper) {
        this.accountRegistry = accountRegistry;
        this.jsonMapper = jsonMapper;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        // Register all tools via auto-discovery
        registerAutoDiscoveredTools();

        logger.info("Registered {} email tools", toolRegistrations.size());
    }

    /**
     * Gets tool registrations for the MCP server builder.
     */
    public List<SyncToolSpecification> getToolRegistrations() {
        return toolRegistrations;
    }

    /**
     * Gets the number of registered tools.
     */
    public int getToolCount() {
        return toolRegistrations.size();
    }

    /**
     * Discovers and registers all tools in the tool package using reflection.
     */
    private void registerAutoDiscoveredTools() {
        logger.info("Auto-discovering tools...");

        // Create ToolContext with AccountRegistry
        ToolContext toolContext = new ToolContext(accountRegistry);

        // Discover all tools
        List<BaseTool> discoveredTools = ToolRegistry.discoverTools(toolContext);
        logger.info("Discovered {} tools via auto-discovery", discoveredTools.size());

        // Register each discovered tool
        for (BaseTool tool : discoveredTools) {
            registerAutoDiscoveredTool(tool);
        }
    }

    /**
     * Registers a single auto-discovered tool.
     */
    private void registerAutoDiscoveredTool(BaseTool baseTool) {
        Tool tool = Tool.builder()
            .name(baseTool.getName())
            .description(baseTool.getDescription())
            .inputSchema(jsonMapper, baseTool.getInputSchema())
            .build();

        SyncToolSpecification spec = new SyncToolSpecification(
            tool,
            (exchange, arguments) -> {
                long startTime = System.currentTimeMillis();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) arguments;
                    Object result = baseTool.execute(args).join();
                    long executionTime = System.currentTimeMillis() - startTime;

                    String jsonResult = addPerformanceMetrics(result, executionTime);

                    logger.info("Tool {} executed in {}ms, response size: {} bytes",
                        baseTool.getName(), executionTime, jsonResult.length());

                    if (jsonResult.length() > 100000) {
                        logger.warn("Large response detected ({} bytes) - may cause client timeout",
                            jsonResult.length());
                    }

                    return new CallToolResult(List.of(new TextContent(jsonResult)), false);
                } catch (Exception e) {
                    long executionTime = System.currentTimeMillis() - startTime;
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    logger.error("Tool {} failed after {}ms: {}", baseTool.getName(), executionTime, cause.getMessage());
                    return new CallToolResult(List.of(new TextContent("Error: " + cause.getMessage())), true);
                }
            }
        );

        toolRegistrations.add(spec);
    }

    /**
     * Adds performance metrics to the result.
     */
    private String addPerformanceMetrics(Object result, long executionTimeMs) {
        try {
            if (result instanceof JsonNode) {
                JsonNode jsonNode = (JsonNode) result;

                // If it already has performance metrics, don't override
                if (jsonNode.has("performance")) {
                    return jsonNode.toString();
                }

                // Add top-level performance metrics
                if (jsonNode.isObject()) {
                    ObjectNode objectNode = (ObjectNode) jsonNode;
                    ObjectNode perfNode = objectMapper.createObjectNode();
                    perfNode.put("total_ms", executionTimeMs);
                    objectNode.set("performance", perfNode);
                    return objectNode.toString();
                } else {
                    // For non-object JsonNode (array, primitive), wrap it
                    ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.set("result", jsonNode);
                    ObjectNode perfNode = objectMapper.createObjectNode();
                    perfNode.put("total_ms", executionTimeMs);
                    wrapper.set("performance", perfNode);
                    return wrapper.toString();
                }
            } else if (result instanceof String) {
                // Try to parse as JSON
                try {
                    JsonNode parsed = objectMapper.readTree((String) result);
                    return addPerformanceMetrics(parsed, executionTimeMs);
                } catch (Exception e) {
                    // Not valid JSON, wrap the string result
                    ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.put("result", (String) result);
                    ObjectNode perfNode = objectMapper.createObjectNode();
                    perfNode.put("total_ms", executionTimeMs);
                    wrapper.set("performance", perfNode);
                    return wrapper.toString();
                }
            } else {
                // For other types, serialize and wrap
                String serialized = objectMapper.writeValueAsString(result);
                try {
                    JsonNode parsed = objectMapper.readTree(serialized);
                    return addPerformanceMetrics(parsed, executionTimeMs);
                } catch (Exception e) {
                    ObjectNode wrapper = objectMapper.createObjectNode();
                    wrapper.put("result", serialized);
                    ObjectNode perfNode = objectMapper.createObjectNode();
                    perfNode.put("total_ms", executionTimeMs);
                    wrapper.set("performance", perfNode);
                    return wrapper.toString();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to add performance metrics: {}", e.getMessage());
            // Fallback: return original result as-is
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception ex) {
                return result instanceof String ? (String) result : result.toString();
            }
        }
    }
}
