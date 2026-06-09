/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.tool.account;

import net.ultrabase.mcp.email.client.OAuthManager;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.config.AccountRegistry;
import net.ultrabase.mcp.email.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the "re-authorize succeeds but auth keeps failing until restart" bug.
 *
 * <p>Root cause: a vault-backed re-authorization wrote the fresh tokens to the vault and bumped the
 * in-memory expiry to a future time, but left the <em>stale</em> access/refresh token on the live
 * {@link AccountConfig}. The running client reads the token from the config (not the vault) and,
 * seeing a future expiry, never refreshed — so it kept presenting the dead token until a restart
 * re-hydrated the config from the vault. The fix updates the in-memory tokens unconditionally; this
 * test pins that invariant on the vault-backed path, where the bug lived.
 *
 * @author César Obach
 */
class ReauthorizeEmailAccountTest {

    /**
     * A real registry whose {@link #save()} never touches disk (so the test cannot clobber the
     * developer's actual {@code ~/.config/ultrapro/email/config.json}), while keeping the real
     * {@link #addAccount} behaviour (cache invalidation). Avoids mocking a concrete class, which
     * the inline mock maker cannot do on current JDKs.
     */
    private static final class RecordingRegistry extends AccountRegistry {
        AccountConfig added;
        int saves;

        @Override
        public void addAccount(AccountConfig config) {
            this.added = config;
            super.addAccount(config);
        }

        @Override
        public void save() {
            this.saves++; // no disk write
        }
    }

    /** A vault-backed OAuth account whose in-memory tokens are stale (the pre-fix failure state). */
    private static AccountConfig staleVaultBackedConfig() {
        AccountConfig config = new AccountConfig("claude", "claude.amodei@gmail.com");
        config.setImapAuthMethod(AccountConfig.AuthMethod.OAUTH2);
        config.setSmtpAuthMethod(AccountConfig.AuthMethod.OAUTH2);
        config.setOauthAccessToken("OLD_access_expired");
        config.setOauthRefreshToken("OLD_refresh_revoked");
        config.setOauthTokenExpiry(Instant.now().minusSeconds(3600)); // already expired
        config.setOauthTokensInVault(true);
        return config;
    }

    @Test
    void vaultBacked_appliesFreshTokensInMemory_notJustExpiry() {
        AccountConfig config = staleVaultBackedConfig();
        RecordingRegistry registry = new RecordingRegistry();
        ReauthorizeEmailAccount tool = new ReauthorizeEmailAccount(new ToolContext(registry));

        Instant future = Instant.now().plusSeconds(3600);
        OAuthManager.OAuthTokens fresh = new OAuthManager.OAuthTokens("NEW_access", "NEW_refresh", future);

        // Vault-backed path (tokensStoredSecurely = true) — exactly where the bug lived.
        tool.applyTokensToConfig(config, fresh, true);

        // The regression guard: BOTH secrets are refreshed in memory, not only the expiry.
        assertEquals("NEW_access", config.getOauthAccessToken(),
            "live access token must be the freshly authorized one");
        assertEquals("NEW_refresh", config.getOauthRefreshToken(),
            "live refresh token must be the freshly authorized one");
        assertEquals(future, config.getOauthTokenExpiry());
        assertTrue(config.isOauthTokensInVault());

        // Cached client invalidated (account re-added) and config persisted.
        assertSame(config, registry.added);
        assertEquals(1, registry.saves);
    }

    @Test
    void fallbackPath_alsoRefreshesInMemoryTokens() {
        AccountConfig config = staleVaultBackedConfig();
        RecordingRegistry registry = new RecordingRegistry();
        ReauthorizeEmailAccount tool = new ReauthorizeEmailAccount(new ToolContext(registry));

        Instant future = Instant.now().plusSeconds(3600);
        OAuthManager.OAuthTokens fresh = new OAuthManager.OAuthTokens("NEW_access", "NEW_refresh", future);

        // No Secret Management available (tokensStoredSecurely = false): tokens fall back to config.
        tool.applyTokensToConfig(config, fresh, false);

        assertEquals("NEW_access", config.getOauthAccessToken());
        assertEquals("NEW_refresh", config.getOauthRefreshToken());
        assertEquals(future, config.getOauthTokenExpiry());
        assertFalse(config.isOauthTokensInVault());
        assertSame(config, registry.added);
        assertEquals(1, registry.saves);
    }
}
