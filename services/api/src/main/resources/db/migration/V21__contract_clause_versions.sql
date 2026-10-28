-- =====================================================================================
-- V21 — Clauses belong to a contract version
--
-- D-002 requires that a change to a term in a new version is visible to the user. That needs
-- two things the schema did not have together: a version of the contract, and the clause set
-- as it stood in that version.
--
-- contract_versions already existed, but contract_clauses hung off the contract itself. Without
-- a version reference there is no "how it was before", so a diff could only compare a contract
-- with itself. The column is nullable on purpose: clauses recorded before this migration, and
-- clauses read from a document that has no version yet, are still valid - they simply cannot
-- take part in a comparison until somebody says which version they belong to.
-- =====================================================================================

ALTER TABLE contract_clauses
    ADD COLUMN version_id uuid REFERENCES contract_versions (id) ON DELETE SET NULL;

CREATE INDEX contract_clauses_version_idx ON contract_clauses (version_id);

COMMENT ON COLUMN contract_clauses.version_id IS
    'The contract version this wording belongs to. NULL means "not assigned to a version yet", which '
    'is different from "unchanged": a clause without a version cannot take part in a comparison.';
