/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.config.ConfigLoader;
import net.ultrabase.mcp.email.tool.BaseTool;
import net.ultrabase.mcp.email.tool.ToolContext;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Updates CRUDLEX permissions for an email account.
 *
 * @author César Obach
 */
public class UpdateAccountPermissions extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public UpdateAccountPermissions(ToolContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "update_account_permissions";
    }

    @Override
    public String getDescription() {
        return "Update CRUDLEX permissions for an email account. " +
            "Permissions: CREATE, READ, UPDATE, DELETE, LIST, EXPORT, EXECUTE. " +
            "Use pipe-separated values (e.g., 'READ|LIST|UPDATE') or aliases: FULL, READONLY, SAFE, NO_SEND, NO_DELETE.";
    }

    @Override
    public String getInputSchema() {
        return schema(
            "permissions", "string", "New permissions. Use pipe-separated values like 'READ|LIST|UPDATE' or aliases: " +
                "FULL (all), READONLY (LIST|READ), SAFE (LIST|READ|UPDATE), NO_SEND (all except EXECUTE), NO_DELETE (all except DELETE)."
        );
    }

    @Override
    public CompletableFuture<?> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            String accountName = resolveAccount(args);
            String permissionsStr = getString(args, "permissions");

            AccountConfig config = context.accountRegistry().getAccount(accountName);
            String oldPerms = ConfigLoader.formatPermissions(config.getPermissions());

            Set<AccountConfig.Permission> newPermissions = parsePermissions(permissionsStr);
            if (!context.accountRegistry().updatePermissions(accountName, newPermissions)) {
                throw new IllegalArgumentException("Failed to update permissions for '" + accountName + "'");
            }

            context.accountRegistry().save();

            String newPerms = ConfigLoader.formatPermissions(newPermissions);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", true);
            result.put("account_name", accountName);
            result.put("old_permissions", oldPerms);
            result.put("new_permissions", newPerms);
            result.put("message", String.format(
                "Updated '%s' permissions: %s -> %s",
                accountName, oldPerms, newPerms
            ));

            return result;
        });
    }

    private Set<AccountConfig.Permission> parsePermissions(String perms) {
        if (perms == null || perms.isBlank()) {
            return EnumSet.allOf(AccountConfig.Permission.class);
        }

        // Handle aliases
        switch (perms.toUpperCase()) {
            case "FULL":
                return EnumSet.allOf(AccountConfig.Permission.class);
            case "READONLY":
                return EnumSet.of(AccountConfig.Permission.LIST, AccountConfig.Permission.READ);
            case "SAFE":
                return EnumSet.of(AccountConfig.Permission.LIST, AccountConfig.Permission.READ,
                    AccountConfig.Permission.UPDATE);
            case "NO_SEND":
                EnumSet<AccountConfig.Permission> noSend = EnumSet.allOf(AccountConfig.Permission.class);
                noSend.remove(AccountConfig.Permission.EXECUTE);
                return noSend;
            case "NO_DELETE":
                EnumSet<AccountConfig.Permission> noDelete = EnumSet.allOf(AccountConfig.Permission.class);
                noDelete.remove(AccountConfig.Permission.DELETE);
                return noDelete;
        }

        // Parse pipe-separated values
        Set<AccountConfig.Permission> result = EnumSet.noneOf(AccountConfig.Permission.class);
        for (String p : perms.split("\\|")) {
            try {
                result.add(AccountConfig.Permission.valueOf(p.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown permission: " + p);
            }
        }
        return result;
    }
}
