-- =====================================================================================
-- V68 - who sent a document, as the platform read it from the letter.
--
-- `documents.sender` held a bare name and was only filled for mail. A letter has its sender at the top: the name, the address, a
-- telephone number, an e-mail address. The platform now reads them and keeps them with the document, and links the document to a
-- contact (found, or created) so that the sender can be found again later.
--   sender_details    address, telephone, e-mail, website, VAT id, IBAN as read from the letter
--   sender_contact_id the contact this sender is
--   sender_source     LETTER read by the platform, MAIL taken from the mail, PERSON typed or confirmed by a person (never overwritten)
-- =====================================================================================
ALTER TABLE documents ADD COLUMN sender_details jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE documents ADD COLUMN sender_contact_id uuid REFERENCES contacts (id) ON DELETE SET NULL;
ALTER TABLE documents ADD COLUMN sender_source varchar(10);
ALTER TABLE documents ADD CONSTRAINT documents_sender_source_chk CHECK (sender_source IS NULL OR sender_source IN ('LETTER', 'MAIL', 'PERSON'));
CREATE INDEX documents_sender_contact_idx ON documents (tenant_id, sender_contact_id) WHERE sender_contact_id IS NOT NULL;
