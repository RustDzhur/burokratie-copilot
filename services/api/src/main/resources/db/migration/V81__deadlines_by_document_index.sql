-- =============================================================================
--  V81 - the client list can count a client's deadlines (Kanzlei edition, §5.5)
-- =============================================================================
--  The Mandanten screen answers "how many deadlines are still open for this client" by joining
--  task_deadlines to documents over source_document_id and grouping by documents.mandant_id. Today the only
--  index on task_deadlines is (task_id) and (tenant_id, due_at); the join side is unindexed, so the count is a
--  sequential scan of every deadline of the account for every read of the screen.
--
--  Partial on purpose: the screen only ever asks for active deadlines that carry a document. A deadline
--  without a document is not a client's deadline, and archived (inactive) ones are history.
-- =============================================================================

create index task_deadlines_document_idx
    on task_deadlines (tenant_id, source_document_id)
    where is_active and source_document_id is not null;

comment on index task_deadlines_document_idx is
    'Open deadlines by source document; the Mandanten screen aggregates them per client (§5.5).';
