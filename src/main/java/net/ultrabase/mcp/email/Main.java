/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import net.ultrabase.mcp.email.config.AccountRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for up-mcp-email MCP Server.
 *
 * @author César Obach
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage();
            System.exit(0);
        }

        if (args.length > 0 && "--version".equals(args[0])) {
            System.out.println("up-mcp-email " + VERSION);
            System.exit(0);
        }

        // Validate arguments
        for (String arg : args) {
            if (!"stdio".equals(arg)) {
                System.err.println("Unknown argument: " + arg);
                printUsage();
                System.exit(1);
            }
        }

        logger.info("Starting up-mcp-email v{}", VERSION);

        try {
            // Load account configuration
            logger.info("Loading email account configuration...");
            AccountRegistry accountRegistry = AccountRegistry.load();

            if (accountRegistry.hasAccounts()) {
                logger.info("Configured {} account(s): {}",
                    accountRegistry.getAccountNames().size(),
                    accountRegistry.getAccountNames());
                logger.info("Default account: {}", accountRegistry.getDefaultAccountName());
            } else {
                logger.info("No accounts configured - use add_email_account tool to add accounts");
            }

            // Create JSON mapper with Java time support
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
            StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

            // Create and configure the MCP server
            EmailServer emailServer = new EmailServer(accountRegistry, jsonMapper);

            // Build server capabilities
            ServerCapabilities capabilities = ServerCapabilities.builder()
                .tools(true)
                .build();

            McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("up-mcp-email", VERSION)
                .capabilities(capabilities)
                .tools(emailServer.getToolRegistrations())
                .build();

            logger.info("MCP server started on stdio with {} tools", emailServer.getToolCount());

            // Block forever - the transport provider handles I/O in its own threads
            Thread.currentThread().join();

        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.err.println();
            System.err.println("No pre-configuration required. Use the add_email_account tool to add accounts:");
            System.err.println();
            System.err.println("For Gmail/Google Workspace:");
            System.err.println("  add_email_account(email_address=\"user@gmail.com\")");
            System.err.println("  → Auto-detects Google, configures OAuth2, opens browser");
            System.err.println();
            System.err.println("For other providers:");
            System.err.println("  add_email_account(");
            System.err.println("    email_address=\"user@example.com\",");
            System.err.println("    password=\"...\",");
            System.err.println("    imap_host=\"imap.example.com\",");
            System.err.println("    smtp_host=\"smtp.example.com\"");
            System.err.println("  )");
            System.exit(1);
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("up-mcp-email - MCP Server for Email (IMAP/SMTP with OAuth2 support)");
        System.out.println();
        System.out.println("Usage: java -jar up-mcp-email.jar [stdio]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  stdio       Run in STDIO mode (default)");
        System.out.println("  --help      Show this help message");
        System.out.println("  --version   Show version");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Accounts are stored in ~/.config/ultrapro/email/config.toml");
        System.out.println("  Use the add_email_account tool to add accounts dynamically");
        System.out.println();
        System.out.println("Supported authentication:");
        System.out.println("  - OAuth2 (Gmail, Google Workspace) - auto-detected via MX records");
        System.out.println("  - Password (any IMAP/SMTP provider)");
    }
}
