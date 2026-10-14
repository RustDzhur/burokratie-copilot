-- A mailbox stopped after three failures of the duplicate-key bug (fixed since) is collected again.
UPDATE mail_accounts
SET status = 'CONNECTED', consecutive_failures = 0, last_sync_error = NULL, last_sync_at = NULL
WHERE status = 'ERROR' AND last_sync_error LIKE '%mail_messages_external_key%';
