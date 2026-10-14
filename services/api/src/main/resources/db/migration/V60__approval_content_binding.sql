-- =====================================================================================
-- V60 - an approval belongs to the content it was given for (task CA-04, decision D-09)
--
-- A confirmation is bound to the action, the recipient, the content and the version that the approver saw. If the material
-- changes afterwards, the confirmation no longer holds: it expires and a new request is raised. Until now an approval
-- carried a token against doing the same thing twice, but nothing tied it to WHAT was approved.
--   approvals.subject_version, content_hash   the version and the fingerprint of the material when the approval was requested
--   outbound_messages.approved_content_hash   the fingerprint of the letter as the approver released it
--   payments.approved_content_hash            the fingerprint of the payment as the approvers confirmed it
-- A fingerprint is a SHA-256 of the fields that make the action what it is (amount, recipient account, reference; subject,
-- text, addressees, attachments), computed by the platform, never taken from a client.
-- =====================================================================================

ALTER TABLE approvals ADD COLUMN subject_version integer;
ALTER TABLE approvals ADD COLUMN content_hash varchar(64);
ALTER TABLE outbound_messages ADD COLUMN approved_content_hash varchar(64);
ALTER TABLE payments ADD COLUMN approved_content_hash varchar(64);

-- An expired approval may have been decided before it expired (the material changed afterwards); the decision stays on record.
ALTER TABLE approvals DROP CONSTRAINT approvals_decision_chk;
ALTER TABLE approvals ADD CONSTRAINT approvals_decision_chk CHECK (
    (status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    OR (status IN ('PENDING', 'CANCELLED') AND decided_at IS NULL)
    OR status = 'EXPIRED'
);
