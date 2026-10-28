-- =====================================================================================
-- V36 - a calendar entry says where it came from
--
-- The platform now creates calendar entries on its own (contract start, end and the end of the notice period).
-- A person who sees "Vertragsende" tomorrow has to be able to ask why, and the answer cannot be the title again:
-- the entry keeps a sentence of explanation and the document it was read from.
-- =====================================================================================

ALTER TABLE calendar_events
    ADD COLUMN note text,
    ADD COLUMN source_document_id uuid REFERENCES documents (id) ON DELETE SET NULL;
