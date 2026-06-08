/*
 * up-mcp-email - MCP Server for Email
 * Copyright (c) 2024 César Obach / ultraBASE
 *
 * Licensed under the MIT License.
 */
package net.ultrabase.mcp.email.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.*;
import net.ultrabase.mcp.email.config.AccountConfig;
import net.ultrabase.mcp.email.gateway.TokenVault;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Email client implementation using Jakarta Mail.
 * Supports password and OAuth2 (XOAUTH2) authentication.
 *
 * @author César Obach
 */
public class EmailClient implements IEmailClient {

    private static final Logger logger = LoggerFactory.getLogger(EmailClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // Refresh token 5 minutes before expiry to avoid edge cases
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

    // Content-ID for embedded signature image
    private static final String SIGNATURE_IMAGE_CID = "signature-logo";

    private final AccountConfig config;
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;
    private final Runnable onTokenRefreshed;
    private TokenVault tokenVault;

    private Session imapSession;
    private Session smtpSession;
    private Store imapStore;

    public EmailClient(AccountConfig config) {
        this(config, null);
    }

    public EmailClient(AccountConfig config, Runnable onTokenRefreshed) {
        this.config = config;
        this.onTokenRefreshed = onTokenRefreshed;
        // Enable GFM tables extension for Markdown conversion
        List<Extension> extensions = List.of(TablesExtension.create());
        this.markdownParser = Parser.builder().extensions(extensions).build();
        this.htmlRenderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    @Override
    public String getAccountName() {
        return config.getAccountName();
    }

    @Override
    public String getEmailAddress() {
        return config.getEmailAddress();
    }

    @Override
    public String getFullName() {
        return config.getFullName();
    }

    // ==================== Connection Management ====================

    private synchronized Store getImapStore() throws MessagingException {
        long startTime = System.currentTimeMillis();

        if (imapStore != null && imapStore.isConnected()) {
            logger.debug("[{}] IMAP store already connected (reusing)", config.getAccountName());
            return imapStore;
        }

        logger.info("[{}] IMAP connection required (store null or disconnected)", config.getAccountName());

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", config.getImapHost());
        props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
        props.put("mail.imaps.ssl.enable", String.valueOf(config.isImapSsl()));
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "30000");

        // Decode RFC 2047/2231 encoded and folded attachment filenames consistently,
        // so getFileName() reconstructs the same value the listing exposed.
        props.put("mail.mime.decodefilename", "true");
        props.put("mail.mime.decodeparameters", "true");

        if (config.isOAuth2()) {
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
            props.put("mail.imaps.sasl.enable", "true");
            props.put("mail.imaps.sasl.mechanisms", "XOAUTH2");
        }

        // Get password/token BEFORE creating store (token refresh may invalidate cached state)
        long tokenStartTime = System.currentTimeMillis();
        String password = config.isOAuth2()
            ? getXOAuth2Token()
            : config.getImapPassword();
        long tokenElapsed = System.currentTimeMillis() - tokenStartTime;
        if (tokenElapsed > 100) {
            logger.info("[{}] Token retrieval took {}ms", config.getAccountName(), tokenElapsed);
        }

        imapSession = Session.getInstance(props);
        imapStore = imapSession.getStore("imaps");

        logger.info("[{}] Connecting to IMAP {}:{}...", config.getAccountName(),
            config.getImapHost(), config.getImapPort());
        long connectStartTime = System.currentTimeMillis();
        imapStore.connect(config.getImapHost(), config.getImapPort(),
            config.getImapUsername(), password);
        long connectElapsed = System.currentTimeMillis() - connectStartTime;

        long totalElapsed = System.currentTimeMillis() - startTime;
        logger.info("[{}] IMAP connected in {}ms (connect: {}ms)",
            config.getAccountName(), totalElapsed, connectElapsed);

        return imapStore;
    }

    private Session getSmtpSession() {
        if (smtpSession != null) {
            return smtpSession;
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "30000");

        if (config.isSmtpSsl()) {
            if (config.getSmtpPort() == 465) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
        }

        if (config.isOAuth2()) {
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            props.put("mail.smtp.sasl.enable", "true");
            props.put("mail.smtp.sasl.mechanisms", "XOAUTH2");
        }

        smtpSession = Session.getInstance(props);
        return smtpSession;
    }

    private String getXOAuth2Token() {
        String token = config.getOauthAccessToken();
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("OAuth2 access token not available for account: " + config.getAccountName());
        }

        // Check if token needs refresh
        Instant expiry = config.getOauthTokenExpiry();
        if (expiry != null && Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS).isAfter(expiry)) {
            logger.info("OAuth2 token expired or expiring soon for {}, refreshing...", config.getAccountName());
            token = refreshOAuthToken();
        }

        return token;
    }

    @Override
    public synchronized void keepAliveRefresh(long marginSeconds) {
        if (!config.isOAuth2()) {
            return;
        }
        Instant expiry = config.getOauthTokenExpiry();
        if (expiry == null) {
            return;
        }
        if (Instant.now().plusSeconds(marginSeconds).isAfter(expiry)) {
            logger.info("[{}] Keep-alive: token within {}s of expiry, refreshing proactively",
                config.getAccountName(), marginSeconds);
            refreshOAuthToken();
        }
    }

    private synchronized TokenVault tokenVault() {
        if (tokenVault == null) {
            tokenVault = TokenVault.fromEnvironment();
        }
        return tokenVault;
    }

    private String refreshOAuthToken() {
        long startTime = System.currentTimeMillis();
        logger.info("[{}] Starting OAuth token refresh...", config.getAccountName());

        String refreshToken = config.getOauthRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new IllegalStateException("OAuth2 refresh token not available for account: " + config.getAccountName());
        }

        // Load OAuth credentials
        String[] credentials = OAuthManager.loadBundledCredentials();
        if (credentials == null) {
            throw new IllegalStateException("OAuth credentials not found. Cannot refresh token.");
        }

        try {
            long apiStartTime = System.currentTimeMillis();
            OAuthManager.OAuthTokens newTokens = OAuthManager.refreshAccessToken(
                refreshToken, credentials[0], credentials[1]);
            long apiElapsed = System.currentTimeMillis() - apiStartTime;
            logger.info("[{}] OAuth API call completed in {}ms", config.getAccountName(), apiElapsed);

            // Update config with new tokens
            config.setOauthAccessToken(newTokens.accessToken());
            config.setOauthTokenExpiry(newTokens.expiry());
            if (newTokens.refreshToken() != null && !newTokens.refreshToken().isEmpty()) {
                config.setOauthRefreshToken(newTokens.refreshToken());
            }

            // Persist the refreshed secrets to the vault when this account is vault-backed,
            // otherwise the next restart would re-hydrate the stale token.
            if (config.isOauthTokensInVault()) {
                if (!tokenVault().storeTokens(config.getAccountName(), newTokens)) {
                    logger.warn("[{}] Refreshed token could not be written to the vault; "
                        + "it may not survive a restart", config.getAccountName());
                }
            }

            long totalElapsed = System.currentTimeMillis() - startTime;
            logger.info("[{}] OAuth2 token refreshed successfully in {}ms", config.getAccountName(), totalElapsed);

            // Notify caller to save (persists expiry + non-secret fields to config)
            if (onTokenRefreshed != null) {
                onTokenRefreshed.run();
            }

            return newTokens.accessToken();
        } catch (OAuthManager.OAuthException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[{}] Failed to refresh OAuth token after {}ms: {}",
                config.getAccountName(), elapsed, e.getMessage());
            throw new IllegalStateException("OAuth token refresh failed: " + e.getMessage(), e);
        }
    }

    private Folder openFolder(String mailboxName, int mode) throws MessagingException {
        long startTime = System.currentTimeMillis();
        logger.debug("[{}] Opening folder: {}", config.getAccountName(), mailboxName);

        Store store = getImapStore();
        long storeElapsed = System.currentTimeMillis() - startTime;

        Folder folder = store.getFolder(mailboxName);
        if (!folder.exists()) {
            throw new IllegalArgumentException("Mailbox not found: " + mailboxName);
        }

        long openStartTime = System.currentTimeMillis();
        folder.open(mode);
        long openElapsed = System.currentTimeMillis() - openStartTime;

        long totalElapsed = System.currentTimeMillis() - startTime;
        if (totalElapsed > 500) {
            logger.info("[{}] Folder {} opened in {}ms (store: {}ms, open: {}ms)",
                config.getAccountName(), mailboxName, totalElapsed, storeElapsed, openElapsed);
        } else {
            logger.debug("[{}] Folder {} opened in {}ms", config.getAccountName(), mailboxName, totalElapsed);
        }

        return folder;
    }

    // ==================== Email Reading ====================

    @Override
    public CompletableFuture<JsonNode> listEmailsMetadata(String mailbox, int page, int pageSize,
                                                           String order, String subject, String fromAddr,
                                                           String toAddr, String since, String before) {
        return CompletableFuture.supplyAsync(() -> {
            long methodStartTime = System.currentTimeMillis();
            logger.info("[{}] listEmailsMetadata started (mailbox={}, page={}, pageSize={})",
                config.getAccountName(), mailbox, page, pageSize);
            try {
                Folder folder = openFolder(mailbox, Folder.READ_ONLY);
                long folderOpenElapsed = System.currentTimeMillis() - methodStartTime;
                try {
                    int totalCount = folder.getMessageCount();
                    logger.debug("[{}] Folder has {} messages", config.getAccountName(), totalCount);

                    // Build search term if filters are provided
                    SearchTerm searchTerm = buildSearchTerm(subject, fromAddr, toAddr, since, before);

                    long fetchStartTime = System.currentTimeMillis();
                    Message[] messages;
                    if (searchTerm != null) {
                        messages = folder.search(searchTerm);
                    } else {
                        messages = folder.getMessages();
                    }

                    // Batch-fetch headers in one IMAP round-trip (critical for performance)
                    // Without this, each getReceivedDate() call in sort triggers individual FETCH
                    FetchProfile fetchProfile = new FetchProfile();
                    fetchProfile.add(FetchProfile.Item.ENVELOPE);  // Subject, From, To, Date, etc.
                    fetchProfile.add(FetchProfile.Item.FLAGS);     // Read/Flagged status
                    folder.fetch(messages, fetchProfile);
                    long fetchElapsed = System.currentTimeMillis() - fetchStartTime;
                    logger.debug("[{}] Fetched {} messages in {}ms",
                        config.getAccountName(), messages.length, fetchElapsed);

                    // Sort by date (now uses cached headers, no network calls)
                    boolean descending = "desc".equalsIgnoreCase(order);
                    Arrays.sort(messages, (m1, m2) -> {
                        try {
                            Date d1 = m1.getReceivedDate();
                            Date d2 = m2.getReceivedDate();
                            if (d1 == null) d1 = new Date(0);
                            if (d2 == null) d2 = new Date(0);
                            return descending ? d2.compareTo(d1) : d1.compareTo(d2);
                        } catch (MessagingException e) {
                            return 0;
                        }
                    });

                    // Paginate
                    int start = (page - 1) * pageSize;
                    int end = Math.min(start + pageSize, messages.length);

                    ObjectNode result = objectMapper.createObjectNode();
                    ArrayNode emails = result.putArray("emails");

                    for (int i = start; i < end; i++) {
                        Message msg = messages[i];
                        emails.add(messageToMetadata(msg));
                    }

                    result.put("page", page);
                    result.put("page_size", pageSize);
                    result.put("total_count", totalCount);
                    result.put("filtered_count", messages.length);
                    result.put("has_more", end < messages.length);

                    long totalElapsed = System.currentTimeMillis() - methodStartTime;
                    logger.info("[{}] listEmailsMetadata completed in {}ms (folderOpen: {}ms, fetch: {}ms)",
                        config.getAccountName(), totalElapsed, folderOpenElapsed, fetchElapsed);

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - methodStartTime;
                logger.error("[{}] Failed to list emails after {}ms: {}",
                    config.getAccountName(), elapsed, e.getMessage(), e);
                throw new RuntimeException("Failed to list emails: " + e.getMessage(), e);
            }
        });
    }

    private SearchTerm buildSearchTerm(String subject, String from, String to, String since, String before) {
        List<SearchTerm> terms = new ArrayList<>();

        if (subject != null && !subject.isEmpty()) {
            terms.add(new SubjectTerm(subject));
        }
        if (from != null && !from.isEmpty()) {
            terms.add(new FromStringTerm(from));
        }
        if (to != null && !to.isEmpty()) {
            terms.add(new RecipientStringTerm(Message.RecipientType.TO, to));
        }
        if (since != null && !since.isEmpty()) {
            Date sinceDate = Date.from(Instant.parse(since));
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, sinceDate));
        }
        if (before != null && !before.isEmpty()) {
            Date beforeDate = Date.from(Instant.parse(before));
            terms.add(new ReceivedDateTerm(ComparisonTerm.LE, beforeDate));
        }

        if (terms.isEmpty()) {
            return null;
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        return new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    private ObjectNode messageToMetadata(Message msg) throws MessagingException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("email_id", String.valueOf(msg.getMessageNumber()));
        node.put("subject", msg.getSubject());

        Address[] fromAddrs = msg.getFrom();
        if (fromAddrs != null && fromAddrs.length > 0) {
            node.put("from", fromAddrs[0].toString());
        }

        ArrayNode recipients = node.putArray("to");
        Address[] toAddrs = msg.getRecipients(Message.RecipientType.TO);
        if (toAddrs != null) {
            for (Address addr : toAddrs) {
                recipients.add(addr.toString());
            }
        }

        Date receivedDate = msg.getReceivedDate();
        if (receivedDate != null) {
            node.put("date", receivedDate.toInstant().toString());
        }

        Flags flags = msg.getFlags();
        node.put("is_read", flags.contains(Flags.Flag.SEEN));
        node.put("is_flagged", flags.contains(Flags.Flag.FLAGGED));

        // Size estimate
        node.put("size_bytes", msg.getSize());

        return node;
    }

    @Override
    public CompletableFuture<JsonNode> getEmailsContent(String mailbox, List<String> emailIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_ONLY);
                try {
                    ObjectNode result = objectMapper.createObjectNode();
                    ArrayNode emails = result.putArray("emails");
                    ArrayNode failed = result.putArray("failed_ids");

                    for (String emailId : emailIds) {
                        try {
                            int msgNum = Integer.parseInt(emailId);
                            Message msg = folder.getMessage(msgNum);
                            emails.add(messageToContent(msg));
                        } catch (Exception e) {
                            failed.add(emailId);
                            logger.warn("Failed to get email {}: {}", emailId, e.getMessage());
                        }
                    }

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to get email content: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to get email content: " + e.getMessage(), e);
            }
        });
    }

    private ObjectNode messageToContent(Message msg) throws MessagingException, IOException {
        ObjectNode node = messageToMetadata(msg);

        // Extract body
        String body = extractTextBody(msg);
        node.put("body", body);

        // Extract attachments info
        ArrayNode attachments = node.putArray("attachments");
        extractAttachments(msg, attachments);

        // Message-ID for threading
        String[] messageIdHeader = msg.getHeader("Message-ID");
        if (messageIdHeader != null && messageIdHeader.length > 0) {
            node.put("message_id", messageIdHeader[0]);
        }

        String[] inReplyTo = msg.getHeader("In-Reply-To");
        if (inReplyTo != null && inReplyTo.length > 0) {
            node.put("in_reply_to", inReplyTo[0]);
        }

        return node;
    }

    private String extractTextBody(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            String text = null;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disposition = bp.getDisposition();
                if (disposition == null || !disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
                    String body = extractTextBody(bp);
                    if (body != null) {
                        if (text == null || bp.isMimeType("text/html")) {
                            text = body;
                        }
                    }
                }
            }
            return text;
        }
        return null;
    }

    private void extractAttachments(Part part, ArrayNode attachments) throws MessagingException, IOException {
        List<Part> parts = collectAttachmentParts(part);
        for (int i = 0; i < parts.size(); i++) {
            Part bp = parts.get(i);
            ObjectNode att = objectMapper.createObjectNode();
            // Stable selector for download_attachment, robust to fragile/encoded filenames.
            att.put("attachment_index", i);
            att.put("filename", bp.getFileName());
            att.put("content_type", bp.getContentType());
            att.put("size_bytes", bp.getSize());
            attachments.add(att);
        }
    }

    @Override
    public CompletableFuture<JsonNode> checkUnread(int maxIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder("INBOX", Folder.READ_ONLY);
                try {
                    int totalCount = folder.getMessageCount();
                    int unreadCount = folder.getUnreadMessageCount();

                    // Get unread messages
                    FlagTerm unseenTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
                    Message[] unreadMessages = folder.search(unseenTerm);

                    // Batch-fetch headers for sorting performance
                    if (unreadMessages.length > 0) {
                        FetchProfile fp = new FetchProfile();
                        fp.add(FetchProfile.Item.ENVELOPE);
                        folder.fetch(unreadMessages, fp);
                    }

                    // Sort by date descending (now uses cached headers)
                    Arrays.sort(unreadMessages, (m1, m2) -> {
                        try {
                            Date d1 = m1.getReceivedDate();
                            Date d2 = m2.getReceivedDate();
                            if (d1 == null) d1 = new Date(0);
                            if (d2 == null) d2 = new Date(0);
                            return d2.compareTo(d1);
                        } catch (MessagingException e) {
                            return 0;
                        }
                    });

                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("total_unread", unreadCount);
                    result.put("total_count", totalCount);

                    ObjectNode byCategory = result.putObject("by_category");
                    ObjectNode primary = byCategory.putObject("primary");
                    primary.put("unread_count", unreadCount);

                    ArrayNode emailIds = primary.putArray("email_ids");
                    ArrayNode emails = primary.putArray("emails");
                    int count = Math.min(maxIds, unreadMessages.length);
                    for (int i = 0; i < count; i++) {
                        Message msg = unreadMessages[i];
                        emailIds.add(String.valueOf(msg.getMessageNumber()));
                        ObjectNode emailInfo = objectMapper.createObjectNode();
                        emailInfo.put("email_id", String.valueOf(msg.getMessageNumber()));
                        emailInfo.put("size_bytes", msg.getSize());
                        emails.add(emailInfo);
                    }
                    primary.put("has_more", unreadMessages.length > maxIds);

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to check unread: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to check unread: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<JsonNode> listFolders() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Store store = getImapStore();
                Folder defaultFolder = store.getDefaultFolder();
                Folder[] folders = defaultFolder.list("*");

                ObjectNode result = objectMapper.createObjectNode();
                ArrayNode foldersArray = result.putArray("folders");

                for (Folder folder : folders) {
                    ObjectNode folderNode = objectMapper.createObjectNode();
                    folderNode.put("name", folder.getFullName());

                    // Get attributes
                    ArrayNode attrs = folderNode.putArray("attributes");
                    if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                        attrs.add("\\HasNoChildren");
                    }
                    if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0) {
                        attrs.add("\\HasChildren");
                    }

                    foldersArray.add(folderNode);
                }

                return result;
            } catch (Exception e) {
                logger.error("Failed to list folders: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to list folders: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<JsonNode> downloadAttachment(String emailId, String attachmentName,
                                                          Integer attachmentIndex, String savePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder("INBOX", Folder.READ_ONLY);
                try {
                    int msgNum = Integer.parseInt(emailId);
                    Message msg = folder.getMessage(msgNum);

                    // Collect attachments in the same order as the listing, so indices align.
                    List<Part> parts = collectAttachmentParts(msg);

                    Part chosen = null;
                    String matchedBy = null;

                    // 1. Explicit index wins — robust against fragile/encoded filenames.
                    if (attachmentIndex != null && attachmentIndex >= 0 && attachmentIndex < parts.size()) {
                        chosen = parts.get(attachmentIndex);
                        matchedBy = "index";
                    }

                    // 2. Robust name match (decode + NFC + collapse whitespace + case-insensitive).
                    if (chosen == null && attachmentName != null && !attachmentName.isEmpty()) {
                        String want = normalizeFilename(attachmentName);
                        for (Part p : parts) {
                            if (normalizeFilename(p.getFileName()).equalsIgnoreCase(want)) {
                                chosen = p;
                                matchedBy = "name";
                                break;
                            }
                        }
                    }

                    // 3. Single-attachment fallback: unambiguous, so honour it.
                    if (chosen == null && parts.size() == 1) {
                        chosen = parts.get(0);
                        matchedBy = "single";
                    }

                    ObjectNode result = objectMapper.createObjectNode();

                    if (chosen == null) {
                        // Actionable error: list what is actually available.
                        ArrayNode available = result.putArray("available_attachments");
                        for (int i = 0; i < parts.size(); i++) {
                            ObjectNode a = objectMapper.createObjectNode();
                            a.put("attachment_index", i);
                            a.put("filename", parts.get(i).getFileName());
                            available.add(a);
                        }
                        String requested = attachmentIndex != null
                            ? ("index " + attachmentIndex)
                            : ("\"" + attachmentName + "\"");
                        result.put("success", false);
                        result.put("email_id", emailId);
                        result.put("error", "Attachment not found: " + requested
                            + ". Use one of the attachment_index values in available_attachments.");
                        return result;
                    }

                    try (InputStream is = chosen.getInputStream()) {
                        Files.copy(is, Path.of(savePath), StandardCopyOption.REPLACE_EXISTING);
                    }

                    result.put("success", true);
                    result.put("email_id", emailId);
                    result.put("attachment_name", chosen.getFileName());
                    result.put("matched_by", matchedBy);
                    result.put("save_path", savePath);
                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to download attachment: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to download attachment: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Collects attachment parts in document order. Used by both listing and download so
     * that {@code attachment_index} refers to the same part in both. A part counts as an
     * attachment when its disposition is ATTACHMENT, or — for senders that omit
     * Content-Disposition — when it carries a filename.
     */
    private List<Part> collectAttachmentParts(Part part) throws MessagingException, IOException {
        List<Part> result = new ArrayList<>();
        collectAttachmentParts(part, result);
        return result;
    }

    private void collectAttachmentParts(Part part, List<Part> out) throws MessagingException, IOException {
        if (!part.isMimeType("multipart/*")) {
            return;
        }
        Multipart mp = (Multipart) part.getContent();
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart bp = mp.getBodyPart(i);
            if (isAttachmentPart(bp)) {
                out.add(bp);
            } else if (bp.isMimeType("multipart/*")) {
                collectAttachmentParts(bp, out);
            }
        }
    }

    private boolean isAttachmentPart(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
            return true;
        }
        // Some senders omit Content-Disposition but still name the part via Content-Type.
        return !part.isMimeType("multipart/*") && part.getFileName() != null;
    }

    /**
     * Normalizes a filename for tolerant matching: MIME-decodes RFC 2047/2231 encoding,
     * applies Unicode NFC, collapses runs of whitespace (folding artifacts) to a single
     * space, and trims.
     */
    private static String normalizeFilename(String name) {
        if (name == null) {
            return "";
        }
        String decoded;
        try {
            decoded = jakarta.mail.internet.MimeUtility.decodeText(name);
        } catch (Exception e) {
            decoded = name;
        }
        decoded = Normalizer.normalize(decoded, Normalizer.Form.NFC);
        return decoded.replaceAll("\\s+", " ").trim();
    }

    // ==================== Email Writing ====================

    @Override
    public CompletableFuture<JsonNode> sendEmail(List<String> recipients, String subject, String body,
                                                  List<String> cc, List<String> bcc, List<String> attachments,
                                                  String inReplyTo, String references, boolean includeSignature,
                                                  String fromAddressOverride, boolean includeHistory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Session session = getSmtpSession();
                MimeMessage message = new MimeMessage(session);

                // From: explicit override > account default_from_address > account email
                String fromAddress;
                if (fromAddressOverride != null && !fromAddressOverride.isEmpty()) {
                    fromAddress = fromAddressOverride;
                } else if (config.getDefaultFromAddress() != null && !config.getDefaultFromAddress().isEmpty()) {
                    fromAddress = config.getDefaultFromAddress();
                } else {
                    fromAddress = config.getEmailAddress();
                }
                String fromName = config.getFullName();
                if (fromName != null && !fromName.isEmpty()) {
                    message.setFrom(new InternetAddress(fromAddress, fromName));
                } else {
                    message.setFrom(new InternetAddress(fromAddress));
                }

                // To
                for (String recipient : recipients) {
                    message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
                }

                // CC
                if (cc != null) {
                    for (String addr : cc) {
                        message.addRecipient(Message.RecipientType.CC, new InternetAddress(addr));
                    }
                }

                // BCC
                if (bcc != null) {
                    for (String addr : bcc) {
                        message.addRecipient(Message.RecipientType.BCC, new InternetAddress(addr));
                    }
                }

                // Subject
                message.setSubject(subject);

                // Threading headers
                if (inReplyTo != null) {
                    message.setHeader("In-Reply-To", inReplyTo);
                }
                if (references != null) {
                    message.setHeader("References", references);
                }

                // Build the quoted original (history) when this is a reply and it was requested.
                String quotedHistory = (includeHistory && inReplyTo != null && !inReplyTo.isEmpty())
                    ? buildQuotedHistory(inReplyTo)
                    : "";

                // Compose body with signature, quoted history and footer (returns HTML)
                String htmlBody = composeEmailBody(body, includeSignature, quotedHistory);

                // Determine if we need multipart/related (embedded image)
                boolean needsRelated = includeSignature && hasSignatureImage();
                boolean hasAttachments = attachments != null && !attachments.isEmpty();

                if (!needsRelated && !hasAttachments) {
                    // Simple case: HTML only
                    message.setContent(htmlBody, "text/html; charset=utf-8");

                } else if (needsRelated && !hasAttachments) {
                    // Case: embedded image, no attachments
                    MimeMultipart related = new MimeMultipart("related");

                    // HTML body
                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(htmlBody, "text/html; charset=utf-8");
                    related.addBodyPart(htmlPart);

                    // Signature image
                    MimeBodyPart imagePart = createSignatureImagePart();
                    if (imagePart != null) {
                        related.addBodyPart(imagePart);
                    }

                    message.setContent(related);

                } else if (!needsRelated && hasAttachments) {
                    // Case: attachments, no embedded image
                    MimeMultipart mixed = new MimeMultipart("mixed");

                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(htmlBody, "text/html; charset=utf-8");
                    mixed.addBodyPart(htmlPart);

                    for (String filePath : attachments) {
                        MimeBodyPart attachPart = new MimeBodyPart();
                        attachPart.attachFile(new File(filePath));
                        mixed.addBodyPart(attachPart);
                    }

                    message.setContent(mixed);

                } else {
                    // Full case: embedded image + attachments
                    MimeMultipart mixed = new MimeMultipart("mixed");

                    // Create related for body + image
                    MimeMultipart related = new MimeMultipart("related");

                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(htmlBody, "text/html; charset=utf-8");
                    related.addBodyPart(htmlPart);

                    MimeBodyPart imagePart = createSignatureImagePart();
                    if (imagePart != null) {
                        related.addBodyPart(imagePart);
                    }

                    // Wrap related in a bodypart for mixed
                    MimeBodyPart relatedWrapper = new MimeBodyPart();
                    relatedWrapper.setContent(related);
                    mixed.addBodyPart(relatedWrapper);

                    // Add attachments
                    for (String filePath : attachments) {
                        MimeBodyPart attachPart = new MimeBodyPart();
                        attachPart.attachFile(new File(filePath));
                        mixed.addBodyPart(attachPart);
                    }

                    message.setContent(mixed);
                }

                message.setSentDate(new Date());

                // Send
                String password = config.isOAuth2()
                    ? getXOAuth2Token()
                    : config.getSmtpPassword();

                try (Transport transport = session.getTransport("smtp")) {
                    transport.connect(config.getSmtpHost(), config.getSmtpPort(),
                        config.getSmtpUsername(), password);
                    transport.sendMessage(message, message.getAllRecipients());
                }

                ObjectNode result = objectMapper.createObjectNode();
                result.put("success", true);
                result.put("from", fromAddress);
                result.put("to", String.join(", ", recipients));
                result.put("subject", subject);

                return result;
            } catch (Exception e) {
                logger.error("Failed to send email: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Composes the final email body as HTML by appending signature and footer as needed.
     *
     * @param body             Original email body
     * @param includeSignature Whether to include account signature
     * @return Composed HTML body with signature and footer
     */
    private String composeEmailBody(String body, boolean includeSignature, String quotedHistoryHtml) {
        // Convert body to HTML first
        String htmlBody = convertToHtml(body);

        StringBuilder finalHtml = new StringBuilder();
        finalHtml.append("<div class=\"email-body\">\n");
        finalHtml.append(htmlBody);
        finalHtml.append("\n</div>\n");

        // Add signature if enabled (with spacing before)
        if (includeSignature) {
            String signatureHtml = composeSignatureHtml();
            if (!signatureHtml.isEmpty()) {
                finalHtml.append("\n<br><br>\n").append(signatureHtml);
            }
        }

        // Append the quoted original message (reply history) below the new content
        if (quotedHistoryHtml != null && !quotedHistoryHtml.isEmpty()) {
            finalHtml.append("\n<br>\n").append(quotedHistoryHtml);
        }

        // Add footer if enabled
        if (config.isIncludeFooter()) {
            finalHtml.append("\n<div class=\"footer\" style=\"margin-top:20px;padding-top:10px;");
            finalHtml.append("border-top:1px solid #ccc;color:#666;font-size:12px;\">\n");
            finalHtml.append("Sent vía ultraPRO\n");
            finalHtml.append("</div>\n");
        }

        return finalHtml.toString();
    }

    /**
     * Composes the signature HTML with embedded image reference if configured.
     * Supports both plain text signatures (converted to HTML) and full HTML signatures.
     *
     * @return HTML of signature, or empty string if no signature configured
     */
    private String composeSignatureHtml() {
        String signature = config.getSignature();
        String imagePath = config.getSignatureImagePath();

        if (signature == null && imagePath == null) {
            return "";
        }

        StringBuilder html = new StringBuilder();

        // Check if signature is already HTML
        if (signature != null && !signature.isBlank()) {
            if (isHtml(signature)) {
                // HTML signature - use as-is (may contain its own embedded images as base64)
                html.append(signature);
            } else {
                // Plain text signature - convert to HTML
                String sigHtml = signature.replace("\n", "<br>\n");
                html.append("<div class=\"signature\">\n");
                html.append(sigHtml);
                html.append("\n</div>\n");

                // Signature image (CID reference) - only for plain text signatures
                // HTML signatures are expected to include their own images
                if (imagePath != null && !imagePath.isBlank()) {
                    html.append("<div class=\"signature-logo\">\n");
                    html.append("<img src=\"cid:").append(SIGNATURE_IMAGE_CID).append("\" alt=\"\">\n");
                    html.append("</div>\n");
                }
            }
        } else if (imagePath != null && !imagePath.isBlank()) {
            // Only image, no text signature
            html.append("<div class=\"signature-logo\">\n");
            html.append("<img src=\"cid:").append(SIGNATURE_IMAGE_CID).append("\" alt=\"\">\n");
            html.append("</div>\n");
        }

        return html.toString();
    }

    /**
     * Checks if a string appears to be HTML content.
     */
    private boolean isHtml(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("<") && trimmed.contains("</");
    }

    /**
     * Checks if a signature image needs to be embedded via CID.
     * Returns false if signature is HTML (HTML signatures contain their own images).
     */
    private boolean hasSignatureImage() {
        // If signature is HTML, it contains its own images - no CID needed
        String signature = config.getSignature();
        if (signature != null && isHtml(signature)) {
            return false;
        }

        String path = config.getSignatureImagePath();
        if (path == null || path.isBlank()) {
            return false;
        }
        return Files.exists(Path.of(path));
    }

    /**
     * Creates MimeBodyPart with signature image and Content-ID for embedding.
     *
     * @return MimeBodyPart with embedded image, or null if image not available
     */
    private MimeBodyPart createSignatureImagePart() throws MessagingException, IOException {
        String imagePath = config.getSignatureImagePath();
        if (imagePath == null) {
            return null;
        }

        Path path = Path.of(imagePath);
        if (!Files.exists(path)) {
            logger.warn("Signature image not found: {}", imagePath);
            return null;
        }

        MimeBodyPart imagePart = new MimeBodyPart();

        // Detect MIME type
        String mimeType = Files.probeContentType(path);
        if (mimeType == null) {
            mimeType = "image/png";  // Default
        }

        // Attach image
        imagePart.attachFile(path.toFile());
        imagePart.setContentID("<" + SIGNATURE_IMAGE_CID + ">");
        imagePart.setDisposition(MimeBodyPart.INLINE);
        imagePart.setHeader("Content-Type", mimeType);

        return imagePart;
    }

    private String convertToHtml(String body) {
        // Check if already HTML
        if (body.trim().startsWith("<") && body.contains("</")) {
            return body;
        }

        // Try to detect Markdown (including GFM tables with |)
        if (body.contains("**") || body.contains("##") || body.contains("```")
            || body.contains("- ") || body.contains("* ") || body.contains("|")) {
            // Looks like Markdown, convert to HTML
            Node document = markdownParser.parse(body);
            String html = htmlRenderer.render(document);
            // Apply professional table styling
            return applyTableStyles(html);
        }

        // Plain text - convert newlines to <br>
        return body.replace("\n", "<br>\n");
    }

    /**
     * Applies minimal zen-style inline CSS to HTML tables.
     * Clean, unobtrusive: just horizontal lines, no heavy backgrounds.
     */
    private String applyTableStyles(String html) {
        if (!html.contains("<table>")) {
            return html;
        }

        // Table: minimal, no outer borders
        html = html.replace("<table>",
            "<table style=\"border-collapse: collapse; margin: 12px 0; font-size: inherit;\">");

        // Header cells: just bold with subtle bottom line
        html = html.replace("<th>",
            "<th style=\"padding: 6px 12px 6px 0; text-align: left; font-weight: 600; " +
            "border-bottom: 1px solid #999;\">");

        // Regular cells: light bottom border only
        html = html.replace("<td>",
            "<td style=\"padding: 6px 12px 6px 0; border-bottom: 1px solid #ddd;\">");

        return html;
    }

    /**
     * Fetches the message identified by {@code messageId} and renders it as an HTML quote
     * block (attribution line + blockquote) to append under a reply. Returns an empty string
     * if the original cannot be located or read, so a reply is never blocked by a missing
     * original.
     */
    private String buildQuotedHistory(String messageId) {
        try {
            Store store = getImapStore();
            Folder folder = findAllMailOrInbox(store);
            if (folder == null) {
                return "";
            }
            folder.open(Folder.READ_ONLY);
            try {
                Message[] found = folder.search(new MessageIDTerm(messageId));
                if (found == null || found.length == 0) {
                    logger.warn("[{}] include_history: original message {} not found",
                        config.getAccountName(), messageId);
                    return "";
                }
                Message original = found[found.length - 1];

                String from = "";
                Address[] fromAddrs = original.getFrom();
                if (fromAddrs != null && fromAddrs.length > 0) {
                    from = fromAddrs[0].toString();
                }
                Date sent = original.getSentDate();
                String when = sent != null
                    ? DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm",
                            java.util.Locale.forLanguageTag("es"))
                        .withZone(ZoneId.systemDefault())
                        .format(sent.toInstant())
                    : "";

                String originalBody = extractTextBody(original);
                String quotedInner;
                if (originalBody == null || originalBody.isBlank()) {
                    quotedInner = "";
                } else if (isHtml(originalBody)) {
                    quotedInner = originalBody;  // already HTML, keep formatting
                } else {
                    quotedInner = escapeHtml(originalBody).replace("\n", "<br>\n");
                }

                String attribution = when.isEmpty()
                    ? escapeHtml(from) + " escribió:"
                    : "El " + escapeHtml(when) + ", " + escapeHtml(from) + " escribió:";

                return "<div class=\"" + quoteCssClass() + "\">\n" + attribution + "<br>\n"
                    + "<blockquote style=\"margin:0 0 0 .8ex;border-left:1px solid #ccc;"
                    + "padding-left:1ex;color:#555;\">\n"
                    + quotedInner
                    + "\n</blockquote>\n</div>\n";
            } finally {
                folder.close(false);
            }
        } catch (Exception e) {
            logger.warn("[{}] include_history: could not build quoted history for {}: {}",
                config.getAccountName(), messageId, e.getMessage());
            return "";
        }
    }

    /**
     * Returns the Gmail "All Mail" folder (special-use {@code \All}) so both received and sent
     * originals are searchable regardless of localized folder names, falling back to INBOX.
     */
    private Folder findAllMailOrInbox(Store store) throws MessagingException {
        try {
            for (Folder f : store.getDefaultFolder().list("*")) {
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    continue;
                }
                if (f instanceof org.eclipse.angus.mail.imap.IMAPFolder) {
                    String[] attrs = ((org.eclipse.angus.mail.imap.IMAPFolder) f).getAttributes();
                    if (attrs != null) {
                        for (String a : attrs) {
                            if ("\\All".equalsIgnoreCase(a)) {
                                return f;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[{}] All Mail lookup failed: {}", config.getAccountName(), e.getMessage());
        }
        Folder inbox = store.getFolder("INBOX");
        return inbox.exists() ? inbox : null;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * CSS class for the reply quote block. {@code email_quote} is our own vendor-neutral
     * marker (the concept the rest of the system reasons about); a vendor hint is appended
     * only when it improves the recipient's rendering — Gmail collapses blocks tagged
     * {@code gmail_quote} behind its "show trimmed content" toggle, and the class is inert
     * everywhere else. Callers never see this; they only toggle include_history.
     */
    private String quoteCssClass() {
        String host = config.getImapHost();
        boolean gmailBacked = host != null && host.toLowerCase().contains("gmail");
        return gmailBacked ? "email_quote gmail_quote" : "email_quote";
    }

    @Override
    public CompletableFuture<JsonNode> markAsRead(String mailbox, List<String> emailIds) {
        return setSeenFlag(mailbox, emailIds, true);
    }

    @Override
    public CompletableFuture<JsonNode> markAsUnread(String mailbox, List<String> emailIds) {
        return setSeenFlag(mailbox, emailIds, false);
    }

    private CompletableFuture<JsonNode> setSeenFlag(String mailbox, List<String> emailIds, boolean seen) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_WRITE);
                try {
                    List<String> successIds = new ArrayList<>();
                    List<String> failedIds = new ArrayList<>();

                    for (String emailId : emailIds) {
                        try {
                            int msgNum = Integer.parseInt(emailId);
                            Message msg = folder.getMessage(msgNum);
                            msg.setFlag(Flags.Flag.SEEN, seen);
                            successIds.add(emailId);
                        } catch (Exception e) {
                            failedIds.add(emailId);
                            logger.warn("Failed to mark email {} as {}: {}",
                                emailId, seen ? "read" : "unread", e.getMessage());
                        }
                    }

                    ObjectNode result = objectMapper.createObjectNode();
                    ArrayNode marked = result.putArray("marked_ids");
                    successIds.forEach(marked::add);
                    ArrayNode failed = result.putArray("failed_ids");
                    failedIds.forEach(failed::add);

                    return result;
                } finally {
                    folder.close(true);
                }
            } catch (Exception e) {
                logger.error("Failed to set seen flag: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to set seen flag: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<JsonNode> deleteEmails(String mailbox, List<String> emailIds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_WRITE);
                try {
                    List<String> deletedIds = new ArrayList<>();
                    List<String> failedIds = new ArrayList<>();

                    for (String emailId : emailIds) {
                        try {
                            int msgNum = Integer.parseInt(emailId);
                            Message msg = folder.getMessage(msgNum);
                            msg.setFlag(Flags.Flag.DELETED, true);
                            deletedIds.add(emailId);
                        } catch (Exception e) {
                            failedIds.add(emailId);
                            logger.warn("Failed to delete email {}: {}", emailId, e.getMessage());
                        }
                    }

                    ObjectNode result = objectMapper.createObjectNode();
                    ArrayNode deleted = result.putArray("deleted_ids");
                    deletedIds.forEach(deleted::add);
                    ArrayNode failed = result.putArray("failed_ids");
                    failedIds.forEach(failed::add);

                    return result;
                } finally {
                    folder.close(true); // Expunge on close
                }
            } catch (Exception e) {
                logger.error("Failed to delete emails: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to delete emails: " + e.getMessage(), e);
            }
        });
    }

    // ==================== Flags ====================

    @Override
    public CompletableFuture<JsonNode> listFlagged(String mailbox, String keyword) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_ONLY);
                try {
                    FlagTerm flaggedTerm = new FlagTerm(new Flags(Flags.Flag.FLAGGED), true);
                    Message[] flaggedMessages = folder.search(flaggedTerm);

                    // Batch-fetch flags for performance
                    if (flaggedMessages.length > 0) {
                        FetchProfile fp = new FetchProfile();
                        fp.add(FetchProfile.Item.FLAGS);
                        folder.fetch(flaggedMessages, fp);
                    }

                    ObjectNode result = objectMapper.createObjectNode();
                    ObjectNode byKeyword = result.putObject("by_keyword");

                    // Group by keyword flags
                    Map<String, List<String>> keywordGroups = new HashMap<>();
                    keywordGroups.put("\\Flagged", new ArrayList<>());

                    for (Message msg : flaggedMessages) {
                        String msgId = String.valueOf(msg.getMessageNumber());
                        keywordGroups.get("\\Flagged").add(msgId);

                        // Check for user-defined flags (keywords)
                        String[] userFlags = msg.getFlags().getUserFlags();
                        for (String flag : userFlags) {
                            keywordGroups.computeIfAbsent(flag, k -> new ArrayList<>()).add(msgId);
                        }
                    }

                    // Filter by keyword if specified
                    if (keyword != null) {
                        List<String> ids = keywordGroups.getOrDefault(keyword, List.of());
                        ObjectNode keywordNode = byKeyword.putObject(keyword);
                        keywordNode.put("count", ids.size());
                        ArrayNode idsArray = keywordNode.putArray("email_ids");
                        ids.forEach(idsArray::add);
                    } else {
                        for (Map.Entry<String, List<String>> entry : keywordGroups.entrySet()) {
                            ObjectNode keywordNode = byKeyword.putObject(entry.getKey());
                            keywordNode.put("count", entry.getValue().size());
                            ArrayNode idsArray = keywordNode.putArray("email_ids");
                            entry.getValue().forEach(idsArray::add);
                        }
                    }

                    result.put("total_flagged", flaggedMessages.length);

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to list flagged: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to list flagged: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<JsonNode> setFlags(String mailbox, List<String> emailIds, List<String> flags) {
        return modifyFlags(mailbox, emailIds, flags, true);
    }

    @Override
    public CompletableFuture<JsonNode> removeFlags(String mailbox, List<String> emailIds, List<String> flags) {
        return modifyFlags(mailbox, emailIds, flags, false);
    }

    private CompletableFuture<JsonNode> modifyFlags(String mailbox, List<String> emailIds,
                                                     List<String> flagNames, boolean add) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_WRITE);
                try {
                    // Build flags object
                    Flags flags = new Flags();
                    for (String flag : flagNames) {
                        if ("\\Flagged".equalsIgnoreCase(flag)) {
                            flags.add(Flags.Flag.FLAGGED);
                        } else if ("\\Seen".equalsIgnoreCase(flag)) {
                            flags.add(Flags.Flag.SEEN);
                        } else if ("\\Deleted".equalsIgnoreCase(flag)) {
                            flags.add(Flags.Flag.DELETED);
                        } else {
                            // User-defined flag (keyword)
                            flags.add(flag);
                        }
                    }

                    List<String> successIds = new ArrayList<>();
                    List<String> failedIds = new ArrayList<>();

                    // Convert to message numbers and validate
                    List<Integer> validMsgNums = new ArrayList<>();
                    for (String emailId : emailIds) {
                        try {
                            validMsgNums.add(Integer.parseInt(emailId));
                        } catch (NumberFormatException e) {
                            failedIds.add(emailId);
                        }
                    }

                    // Batch fetch and set flags (much faster than one by one)
                    if (!validMsgNums.isEmpty()) {
                        int[] msgNumArray = validMsgNums.stream().mapToInt(Integer::intValue).toArray();
                        Message[] messages = folder.getMessages(msgNumArray);

                        // Set flags on all messages at once
                        folder.setFlags(messages, flags, add);

                        // Mark all as success
                        for (int msgNum : msgNumArray) {
                            successIds.add(String.valueOf(msgNum));
                        }
                    }

                    ObjectNode result = objectMapper.createObjectNode();
                    ArrayNode success = result.putArray("success_ids");
                    successIds.forEach(success::add);
                    ArrayNode failed = result.putArray("failed_ids");
                    failedIds.forEach(failed::add);

                    return result;
                } finally {
                    folder.close(true);
                }
            } catch (Exception e) {
                logger.error("Failed to modify flags: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to modify flags: " + e.getMessage(), e);
            }
        });
    }

    // ==================== Status ====================

    @Override
    public CompletableFuture<JsonNode> getStatus() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder("INBOX", Folder.READ_ONLY);
                try {
                    int unreadCount = folder.getUnreadMessageCount();

                    FlagTerm flaggedTerm = new FlagTerm(new Flags(Flags.Flag.FLAGGED), true);
                    Message[] flaggedMessages = folder.search(flaggedTerm);
                    int flaggedCount = flaggedMessages.length;

                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("account_name", config.getAccountName());
                    result.put("email_address", config.getEmailAddress());
                    result.put("unread_count", unreadCount);
                    result.put("flagged_count", flaggedCount);

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to get status: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to get status: " + e.getMessage(), e);
            }
        });
    }
}
