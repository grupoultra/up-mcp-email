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
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private Session imapSession;
    private Session smtpSession;
    private Store imapStore;

    public EmailClient(AccountConfig config) {
        this(config, null);
    }

    public EmailClient(AccountConfig config, Runnable onTokenRefreshed) {
        this.config = config;
        this.onTokenRefreshed = onTokenRefreshed;
        this.markdownParser = Parser.builder().build();
        this.htmlRenderer = HtmlRenderer.builder().build();
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
        if (imapStore != null && imapStore.isConnected()) {
            return imapStore;
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", config.getImapHost());
        props.put("mail.imaps.port", String.valueOf(config.getImapPort()));
        props.put("mail.imaps.ssl.enable", String.valueOf(config.isImapSsl()));
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "30000");

        if (config.isOAuth2()) {
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
            props.put("mail.imaps.sasl.enable", "true");
            props.put("mail.imaps.sasl.mechanisms", "XOAUTH2");
        }

        // Get password/token BEFORE creating store (token refresh may invalidate cached state)
        String password = config.isOAuth2()
            ? getXOAuth2Token()
            : config.getImapPassword();

        imapSession = Session.getInstance(props);
        imapStore = imapSession.getStore("imaps");

        logger.debug("Connecting to IMAP: {}:{}", config.getImapHost(), config.getImapPort());
        imapStore.connect(config.getImapHost(), config.getImapPort(),
            config.getImapUsername(), password);

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

    private String refreshOAuthToken() {
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
            OAuthManager.OAuthTokens newTokens = OAuthManager.refreshAccessToken(
                refreshToken, credentials[0], credentials[1]);

            // Update config with new tokens
            config.setOauthAccessToken(newTokens.accessToken());
            config.setOauthTokenExpiry(newTokens.expiry());
            if (newTokens.refreshToken() != null && !newTokens.refreshToken().isEmpty()) {
                config.setOauthRefreshToken(newTokens.refreshToken());
            }

            logger.info("OAuth2 token refreshed successfully for {}", config.getAccountName());

            // Notify caller to save (if callback provided)
            if (onTokenRefreshed != null) {
                onTokenRefreshed.run();
            }

            return newTokens.accessToken();
        } catch (OAuthManager.OAuthException e) {
            logger.error("Failed to refresh OAuth token for {}: {}", config.getAccountName(), e.getMessage());
            throw new IllegalStateException("OAuth token refresh failed: " + e.getMessage(), e);
        }
    }

    private Folder openFolder(String mailboxName, int mode) throws MessagingException {
        Store store = getImapStore();
        Folder folder = store.getFolder(mailboxName);
        if (!folder.exists()) {
            throw new IllegalArgumentException("Mailbox not found: " + mailboxName);
        }
        folder.open(mode);
        return folder;
    }

    // ==================== Email Reading ====================

    @Override
    public CompletableFuture<JsonNode> listEmailsMetadata(String mailbox, int page, int pageSize,
                                                           String order, String subject, String fromAddr,
                                                           String toAddr, String since, String before) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder(mailbox, Folder.READ_ONLY);
                try {
                    int totalCount = folder.getMessageCount();

                    // Build search term if filters are provided
                    SearchTerm searchTerm = buildSearchTerm(subject, fromAddr, toAddr, since, before);

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

                    return result;
                } finally {
                    folder.close(false);
                }
            } catch (Exception e) {
                logger.error("Failed to list emails: {}", e.getMessage(), e);
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
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disposition = bp.getDisposition();
                if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
                    ObjectNode att = objectMapper.createObjectNode();
                    att.put("filename", bp.getFileName());
                    att.put("content_type", bp.getContentType());
                    att.put("size_bytes", bp.getSize());
                    attachments.add(att);
                }
                extractAttachments(bp, attachments);
            }
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
    public CompletableFuture<JsonNode> downloadAttachment(String emailId, String attachmentName, String savePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Folder folder = openFolder("INBOX", Folder.READ_ONLY);
                try {
                    int msgNum = Integer.parseInt(emailId);
                    Message msg = folder.getMessage(msgNum);

                    // Find and save the attachment
                    boolean found = saveAttachment(msg, attachmentName, savePath);

                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("success", found);
                    result.put("email_id", emailId);
                    result.put("attachment_name", attachmentName);
                    result.put("save_path", savePath);

                    if (!found) {
                        result.put("error", "Attachment not found: " + attachmentName);
                    }

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

    private boolean saveAttachment(Part part, String filename, String savePath)
            throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disposition = bp.getDisposition();
                if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
                    if (filename.equals(bp.getFileName())) {
                        try (InputStream is = bp.getInputStream()) {
                            Files.copy(is, Path.of(savePath));
                        }
                        return true;
                    }
                }
                if (saveAttachment(bp, filename, savePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== Email Writing ====================

    @Override
    public CompletableFuture<JsonNode> sendEmail(List<String> recipients, String subject, String body,
                                                  List<String> cc, List<String> bcc, List<String> attachments,
                                                  String inReplyTo, String references, boolean includeSignature) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Session session = getSmtpSession();
                MimeMessage message = new MimeMessage(session);

                // From
                String fromAddress = config.getEmailAddress();
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

                // Compose body with signature and footer (returns HTML)
                String htmlBody = composeEmailBody(body, includeSignature);

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
    private String composeEmailBody(String body, boolean includeSignature) {
        // Convert body to HTML first
        String htmlBody = convertToHtml(body);

        StringBuilder finalHtml = new StringBuilder();
        finalHtml.append("<div class=\"email-body\">\n");
        finalHtml.append(htmlBody);
        finalHtml.append("\n</div>\n");

        // Add signature if enabled
        if (includeSignature) {
            String signatureHtml = composeSignatureHtml();
            if (!signatureHtml.isEmpty()) {
                finalHtml.append("\n").append(signatureHtml);
            }
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
     *
     * @return HTML of signature with CID reference, or empty string if no signature
     */
    private String composeSignatureHtml() {
        String signature = config.getSignature();
        String imagePath = config.getSignatureImagePath();

        if (signature == null && imagePath == null) {
            return "";
        }

        StringBuilder html = new StringBuilder();

        // Text signature
        if (signature != null && !signature.isBlank()) {
            // Convert signature text to HTML (respect line breaks)
            String sigHtml = signature.replace("\n", "<br>\n");
            html.append("<div class=\"signature\">\n");
            html.append(sigHtml);
            html.append("\n</div>\n");
        }

        // Signature image (CID reference)
        if (imagePath != null && !imagePath.isBlank()) {
            html.append("<div class=\"signature-logo\">\n");
            html.append("<img src=\"cid:").append(SIGNATURE_IMAGE_CID).append("\" alt=\"\">\n");
            html.append("</div>\n");
        }

        return html.toString();
    }

    /**
     * Checks if a signature image is configured and exists.
     */
    private boolean hasSignatureImage() {
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

        // Try to detect Markdown
        if (body.contains("**") || body.contains("##") || body.contains("```")
            || body.contains("- ") || body.contains("* ")) {
            // Looks like Markdown, convert to HTML
            Node document = markdownParser.parse(body);
            return htmlRenderer.render(document);
        }

        // Plain text - convert newlines to <br>
        return body.replace("\n", "<br>\n");
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
