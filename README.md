# up-mcp-email

MCP Server for Email (IMAP/SMTP) with OAuth2 and password authentication support.

## Features

- **20 email tools** for comprehensive email management
- **OAuth2 authentication** for Gmail and Google Workspace (auto-detected via MX records)
- **Password authentication** for any IMAP/SMTP provider
- **Multi-account support** with per-account permissions
- **CRUDLEX permissions** for fine-grained access control

## Tools

### Account Management
- `list_available_accounts` - List configured accounts
- `add_email_account` - Add a new email account (OAuth2 or password)
- `update_email_account` - Update account password/display name
- `set_default_account` - Set the default account

### Email Reading
- `list_emails_metadata` - List email metadata (subject, sender, date)
- `get_emails_content` - Get full email content including body
- `check_unread` - Check for unread emails
- `list_folders` - List mailbox folders
- `download_attachment` - Download email attachments

### Email Writing
- `send_email` - Send an email (supports Markdown, HTML, plain text)
- `mark_as_read` - Mark emails as read
- `mark_as_unread` - Mark emails as unread
- `delete_emails` - Delete emails

### Flags
- `list_flagged` - List flagged emails by keyword
- `set_flag` - Add flags/keywords to emails
- `remove_flag` - Remove flags/keywords from emails

### Status & Permissions
- `get_status` - Get status for all accounts
- `get_account_status_tool` - Get status for a specific account
- `update_account_permissions` - Update CRUDLEX permissions
- `update_status_settings` - Update status reporting settings

## Usage

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
java -jar target/up-mcp-email-1.0.0-SNAPSHOT.jar stdio
```

### With ultraPRO Desktop

1. Copy the JAR to `/Applications/ultraPRO Desktop.app/Contents/Resources/providers/`
2. Add to `~/.ultrapro/providers.json`:

```json
{
  "providers": [
    {
      "id": "email",
      "displayName": "Email (IMAP/SMTP)",
      "type": "STDIO",
      "command": "java -jar '/Applications/ultraPRO Desktop.app/Contents/Resources/providers/up-mcp-email.jar' stdio",
      "enabled": true,
      "autoRestart": true,
      "trustedSource": false
    }
  ]
}
```

## Configuration

Accounts are stored in `~/.config/ultrapro/email/config.json`.

### Adding Gmail Account (OAuth2)

```
add_email_account(email_address="user@gmail.com")
```

This will:
1. Auto-detect Google via MX records
2. Open browser for OAuth2 authorization
3. Store tokens securely

### Adding Generic Account (Password)

```
add_email_account(
    email_address="user@example.com",
    password="app-password",
    imap_host="imap.example.com",
    smtp_host="smtp.example.com"
)
```

## Permissions

CRUDLEX permissions control what operations are allowed:

| Permission | Operations |
|------------|------------|
| CREATE | (not used) |
| READ | Read email content |
| UPDATE | Mark as read/unread, set flags |
| DELETE | Delete emails |
| LIST | List emails, check unread |
| EXPORT | Download attachments |
| EXECUTE | Send emails |

### Permission Aliases

- `FULL` - All permissions
- `READONLY` - LIST, READ
- `SAFE` - LIST, READ, UPDATE
- `NO_SEND` - All except EXECUTE
- `NO_DELETE` - All except DELETE

## Requirements

- Java 17+
- For Gmail: OAuth credentials (bundled or custom)

## License

MIT License - Copyright (c) 2024 César Obach / ultraBASE
