-- =====================================================================================
-- V67 - proof of what a person agreed to, and when.
--
-- Whoever registers accepts the terms and has read the privacy notice; a business also concludes the data processing
-- agreement (Art. 28 GDPR). The proof is the document, its version and the time. Nothing else is stored: no address of the
-- device, no browser (data minimisation, Art. 5(1)(c)). The row stays after the account is closed only as long as the
-- user row does, so deleting the person deletes their proof with them.
-- =====================================================================================
CREATE TABLE legal_consents (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    document     varchar(20) NOT NULL,
    version      varchar(20) NOT NULL,
    accepted_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT legal_consents_document_chk CHECK (document IN ('AGB', 'DATENSCHUTZ', 'AVV'))
);
CREATE INDEX legal_consents_user_idx ON legal_consents (user_id, document, accepted_at DESC);
