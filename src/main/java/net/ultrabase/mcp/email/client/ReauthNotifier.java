/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot desktop alert for terminal OAuth failures (invalid_grant).
 *
 * <p>Motivation: on 2026-06-25 three accounts lost their refresh tokens simultaneously and the
 * server logged a WARN every 10 minutes for a week without anyone noticing — the failure mode is
 * silent because the MCP server has no UI. A single desktop notification on the transition to
 * "re-authorization required" makes the condition visible the moment it happens.
 *
 * <p>Best-effort by design: notification failure must never break the caller (macOS only; other
 * platforms just log). Fired at most once per account per process (the transition guard lives in
 * the caller via {@code AccountConfig.isOauthReauthRequired()}).
 *
 * @author César Obach
 */
final class ReauthNotifier {

    private static final Logger logger = LoggerFactory.getLogger(ReauthNotifier.class);

    private ReauthNotifier() {}

    /**
     * Shows a macOS notification telling the user the account needs re-authorization.
     *
     * @param accountName  MCP account name (e.g. "claude")
     * @param emailAddress the mailbox address, for display
     */
    static void notifyReauthRequired(String accountName, String emailAddress) {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase().contains("mac")) {
            logger.info("[{}] Desktop notification skipped (unsupported OS: {})", accountName, osName);
            return;
        }
        String title = "ultraPRO Email: reautorización requerida";
        String body = String.format("La cuenta '%s' (%s) perdió su token OAuth. "
            + "Ejecuta reauthorize_email_account.", accountName, emailAddress);
        String script = String.format(
            "display notification \"%s\" with title \"%s\" sound name \"Basso\"",
            escapeAppleScript(body), escapeAppleScript(title));
        try {
            new ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start();
            logger.info("[{}] Desktop notification fired: re-authorization required", accountName);
        } catch (Exception e) {
            logger.warn("[{}] Could not fire desktop notification: {}", accountName, e.getMessage());
        }
    }

    private static String escapeAppleScript(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
