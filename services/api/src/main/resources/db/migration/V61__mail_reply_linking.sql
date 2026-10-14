-- =====================================================================================
-- V61 - an incoming letter finds the case it answers (task CA-06)
--
-- A reply is linked to a case by the headers of the mail itself (In-Reply-To and References name the Message-ID the platform
-- got from the provider when it sent the original). When the headers point to one case the link is made; when they point to
-- several, or when only weaker signs exist (the case reference in the subject, the sender being the counterparty), the
-- candidates are kept as a SUGGESTION for a person - nothing is linked silently on a guess.
-- =====================================================================================

ALTER TABLE mail_messages ADD COLUMN case_link_status varchar(12) NOT NULL DEFAULT 'NONE';
ALTER TABLE mail_messages ADD COLUMN reply_candidates jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE mail_messages ADD CONSTRAINT mail_messages_case_link_chk CHECK (case_link_status IN ('NONE', 'LINKED', 'SUGGESTED'));

-- A link made before this migration (a person attached the mail to a case) counts as a link.
UPDATE mail_messages SET case_link_status = 'LINKED' WHERE case_id IS NOT NULL;

CREATE INDEX mail_messages_in_reply_to_idx ON mail_messages (tenant_id, in_reply_to) WHERE in_reply_to IS NOT NULL;
CREATE INDEX outbound_messages_provider_id_idx ON outbound_messages (tenant_id, provider_message_id) WHERE provider_message_id IS NOT NULL;
