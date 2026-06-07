/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.client;

import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.config.AccountRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background sweep that proactively refreshes OAuth2 access tokens before they expire.
 *
 * <p>This is not what cures forced re-authorization (that's fixed by reliable token
 * persistence); it keeps tokens fresh so the first tool call after an idle period pays
 * no refresh latency, and keeps the refresh token exercised so it does not lapse from
 * disuse. Refreshed tokens are persisted by {@link EmailClient} (vault + config).
 *
 * <p>Mirrors the daemon-scheduler idiom used by the meetings provider's CalendarWatcher.
 *
 * @author César Obach
 */
public class TokenKeepAlive {

    private static final Logger logger = LoggerFactory.getLogger(TokenKeepAlive.class);

    /** How often the sweep runs. */
    private static final long INTERVAL_SECONDS = 600;   // 10 minutes
    /** Refresh tokens expiring within this window (>= interval to avoid gaps). */
    private static final long REFRESH_MARGIN_SECONDS = 900;  // 15 minutes

    private final AccountRegistry registry;
    private ScheduledExecutorService scheduler;

    public TokenKeepAlive(AccountRegistry registry) {
        this.registry = registry;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "email-token-keepalive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sweep,
            INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("Token keep-alive started (interval: {}s, margin: {}s)",
            INTERVAL_SECONDS, REFRESH_MARGIN_SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            logger.info("Token keep-alive stopped");
        }
    }

    private void sweep() {
        try {
            for (AccountConfig config : registry.getAccounts()) {
                if (!config.isOAuth2()) {
                    continue;
                }
                String name = config.getAccountName();
                try {
                    registry.getEmailClient(name).keepAliveRefresh(REFRESH_MARGIN_SECONDS);
                } catch (Exception e) {
                    logger.warn("Keep-alive refresh failed for account '{}': {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Token keep-alive sweep failed: {}", e.getMessage());
        }
    }
}
