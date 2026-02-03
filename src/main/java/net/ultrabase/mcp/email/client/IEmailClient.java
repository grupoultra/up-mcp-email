/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for email client operations (IMAP/SMTP).
 * Implementations handle both password and OAuth2 authentication.
 *
 * @author César Obach
 */
public interface IEmailClient {

    // ==================== Account Info ====================

    /**
     * Gets the account name.
     */
    String getAccountName();

    /**
     * Gets the email address.
     */
    String getEmailAddress();

    /**
     * Gets the display name.
     */
    String getFullName();

    // ==================== Email Reading ====================

    /**
     * Lists email metadata (without body content).
     *
     * @param mailbox    Mailbox name (e.g., "INBOX")
     * @param page       Page number (1-based)
     * @param pageSize   Number of emails per page
     * @param order      Sort order ("asc" or "desc")
     * @param subject    Filter by subject (optional)
     * @param fromAddr   Filter by sender (optional)
     * @param toAddr     Filter by recipient (optional)
     * @param since      Filter by date after (optional, ISO format)
     * @param before     Filter by date before (optional, ISO format)
     * @return List of email metadata
     */
    CompletableFuture<JsonNode> listEmailsMetadata(String mailbox, int page, int pageSize,
                                                    String order, String subject, String fromAddr,
                                                    String toAddr, String since, String before);

    /**
     * Gets full email content including body.
     *
     * @param mailbox  Mailbox name
     * @param emailIds List of email IDs
     * @return Email content
     */
    CompletableFuture<JsonNode> getEmailsContent(String mailbox, List<String> emailIds);

    /**
     * Checks for unread emails.
     *
     * @param maxIds Maximum number of unread email IDs to return per category
     * @return Unread email information
     */
    CompletableFuture<JsonNode> checkUnread(int maxIds);

    /**
     * Lists all mailbox folders.
     *
     * @return List of folder names and attributes
     */
    CompletableFuture<JsonNode> listFolders();

    /**
     * Downloads an email attachment.
     *
     * @param emailId        Email ID
     * @param attachmentName Attachment filename
     * @param savePath       Path to save the attachment
     * @return Download result
     */
    CompletableFuture<JsonNode> downloadAttachment(String emailId, String attachmentName, String savePath);

    // ==================== Email Writing ====================

    /**
     * Sends an email.
     *
     * @param recipients  List of recipient addresses
     * @param subject     Email subject
     * @param body        Email body (Markdown, HTML, or plain text)
     * @param cc          CC addresses (optional)
     * @param bcc         BCC addresses (optional)
     * @param attachments List of file paths to attach (optional)
     * @param inReplyTo   Message-ID being replied to (optional)
     * @param references  Thread Message-IDs (optional)
     * @return Send result
     */
    CompletableFuture<JsonNode> sendEmail(List<String> recipients, String subject, String body,
                                           List<String> cc, List<String> bcc, List<String> attachments,
                                           String inReplyTo, String references);

    /**
     * Marks emails as read.
     *
     * @param mailbox  Mailbox name
     * @param emailIds Email IDs to mark
     * @return Result with marked and failed IDs
     */
    CompletableFuture<JsonNode> markAsRead(String mailbox, List<String> emailIds);

    /**
     * Marks emails as unread.
     *
     * @param mailbox  Mailbox name
     * @param emailIds Email IDs to mark
     * @return Result with marked and failed IDs
     */
    CompletableFuture<JsonNode> markAsUnread(String mailbox, List<String> emailIds);

    /**
     * Deletes emails.
     *
     * @param mailbox  Mailbox name
     * @param emailIds Email IDs to delete
     * @return Result with deleted and failed IDs
     */
    CompletableFuture<JsonNode> deleteEmails(String mailbox, List<String> emailIds);

    // ==================== Flags ====================

    /**
     * Lists flagged emails.
     *
     * @param mailbox Mailbox name
     * @param keyword Filter by specific keyword (optional)
     * @return Flagged emails by keyword
     */
    CompletableFuture<JsonNode> listFlagged(String mailbox, String keyword);

    /**
     * Sets flags on emails.
     *
     * @param mailbox  Mailbox name
     * @param emailIds Email IDs
     * @param flags    Flags to add
     * @return Result with success and failed IDs
     */
    CompletableFuture<JsonNode> setFlags(String mailbox, List<String> emailIds, List<String> flags);

    /**
     * Removes flags from emails.
     *
     * @param mailbox  Mailbox name
     * @param emailIds Email IDs
     * @param flags    Flags to remove
     * @return Result with success and failed IDs
     */
    CompletableFuture<JsonNode> removeFlags(String mailbox, List<String> emailIds, List<String> flags);

    // ==================== Status ====================

    /**
     * Gets account status (unread/flagged counts).
     *
     * @return Account status
     */
    CompletableFuture<JsonNode> getStatus();
}
