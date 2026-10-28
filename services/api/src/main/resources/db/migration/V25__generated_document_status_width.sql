-- =====================================================================================
-- V25 — the status the schema asks for did not fit in the column
--
-- generated_documents.status is varchar(20). Its own CHECK constraint allows
-- 'BLOCKED_UNRESOLVED_FIELDS', which is 25 characters, and a second constraint REQUIRES that value
-- whenever unresolved_fields is not empty:
--
--     CHECK (status <> 'BLOCKED_UNRESOLVED_FIELDS' OR jsonb_array_length(unresolved_fields) > 0)
--
-- So the schema stated an intention — a document with blanks is not a finished document — and made
-- the state that expresses it impossible to write. An INSERT with unresolved fields failed with
-- "value too long for type character varying(20)", which is a strange way to be told that a letter
-- has a blank in it.
--
-- The constraint is the intention and the width is a detail, so the width is what changes. Nothing
-- that fits today stops fitting.
-- =====================================================================================

ALTER TABLE generated_documents
    ALTER COLUMN status TYPE varchar(40);

COMMENT ON COLUMN generated_documents.status IS
    'DRAFT, GENERATED, BLOCKED_UNRESOLVED_FIELDS or FAILED. The column is wide enough for the longest
     of them; before V25 it was not, so the state the CHECK requires could not be stored.';
