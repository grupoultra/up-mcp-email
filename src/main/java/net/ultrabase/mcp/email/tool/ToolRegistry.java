/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto-discovers and registers all tool handlers in the tool package.
 * Uses reflection to scan for BaseTool subclasses and instantiates them.
 *
 * @author César Obach
 */
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);
    private static final String TOOLS_PACKAGE = "net.ultrabase.mcp.email.tool";

    /**
     * Discovers all BaseTool subclasses in the tool package and instantiates them.
     *
     * @param context The tool context with shared dependencies
     * @return List of discovered tool instances
     * @throws RuntimeException if tool discovery or instantiation fails
     */
    public static List<BaseTool> discoverTools(ToolContext context) {
        logger.info("Discovering tools in package: {}", TOOLS_PACKAGE);

        List<BaseTool> tools = new ArrayList<>();

        try {
            // Use reflection to scan for subclasses of BaseTool
            Reflections reflections = new Reflections(TOOLS_PACKAGE, Scanners.SubTypes);
            Set<Class<? extends BaseTool>> toolClasses = reflections.getSubTypesOf(BaseTool.class);

            logger.debug("Found {} potential tool classes", toolClasses.size());

            for (Class<? extends BaseTool> toolClass : toolClasses) {
                // Skip abstract classes and BaseTool itself
                if (Modifier.isAbstract(toolClass.getModifiers()) || toolClass == BaseTool.class) {
                    logger.debug("Skipping abstract class: {}", toolClass.getName());
                    continue;
                }

                try {
                    // Instantiate tool via constructor that takes ToolContext
                    BaseTool tool = toolClass.getConstructor(ToolContext.class).newInstance(context);
                    tools.add(tool);
                    logger.debug("Registered tool: {} ({})", tool.getName(), toolClass.getSimpleName());
                } catch (Exception e) {
                    logger.error("Failed to instantiate tool: {}", toolClass.getName(), e);
                    throw new RuntimeException("Failed to instantiate tool: " + toolClass.getName(), e);
                }
            }

            logger.info("Successfully discovered {} tools", tools.size());

            // Validate we found the expected number of tools
            if (tools.isEmpty()) {
                logger.warn("No tools discovered! This may indicate a configuration problem.");
            }

        } catch (Exception e) {
            logger.error("Tool discovery failed", e);
            throw new RuntimeException("Tool discovery failed", e);
        }

        return tools;
    }
}
