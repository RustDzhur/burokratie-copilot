-- A mail message a person deleted stays in the table (marked), so that the next collection of the mailbox does not
-- fetch it again: the unique key on the external id still finds it.
ALTER TABLE mail_messages ADD COLUMN deleted_at timestamptz;
CREATE INDEX mail_messages_live_idx ON mail_messages (tenant_id, received_at DESC) WHERE deleted_at IS NULL;
