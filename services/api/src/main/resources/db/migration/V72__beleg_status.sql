-- =============================================================================
--  V72 - the business status of a Beleg (Kanzlei edition, §8.1)
-- =============================================================================
--  The pipeline status answers "what did the machine do with this file"; the bookkeeper asks a different
--  question: "where is this voucher in my work". The two are separate axes on purpose - a file can be fully
--  extracted (READY) and still untouched in the queue (NEU), and a file can be exported and later fail a
--  check - so the business status gets its own column instead of overloading the pipeline one.
--
--  The values are the specification's: NEU -> OFFEN -> ZU_PRUEFEN -> EXPORTIERT, plus FEHLER from anywhere.
-- =============================================================================

alter table documents
    add column beleg_status text not null default 'NEU';

alter table documents
    add constraint documents_beleg_status_chk
        check (beleg_status in ('NEU', 'OFFEN', 'ZU_PRUEFEN', 'EXPORTIERT', 'FEHLER'));

-- Backfill from what the pipeline already knows, so an existing installation does not show every old document
-- as brand new. States that carry no work meaning for the office (quarantine, duplicate, cancelled, legal hold)
-- are left at NEU: they are not in the bookkeeper's queue either way.
update documents set beleg_status = 'FEHLER'
 where processing_status in ('FAILED', 'UNSUPPORTED');

update documents set beleg_status = 'ZU_PRUEFEN'
 where processing_status = 'REVIEW_REQUIRED';

update documents set beleg_status = 'OFFEN'
 where processing_status in ('READY', 'ACTION_PENDING', 'COMPLETED', 'ARCHIVED');

-- The queue reads by status within one account, newest first: the index serves both the counters and the list.
create index documents_beleg_status_idx
    on documents (tenant_id, beleg_status, created_at desc);

comment on column documents.beleg_status is
    'Business status in the Kanzlei queue: NEU, OFFEN, ZU_PRUEFEN, EXPORTIERT, FEHLER (§8.1).';

-- -----------------------------------------------------------------------------
--  The client a voucher belongs to (§5.2, §8.3)
-- -----------------------------------------------------------------------------
--  Nullable on purpose: a voucher without a client is a normal, visible state - the queue calls it
--  "Ohne Mandant" and refuses to export it - and it must never be guessed. The routing rules that fill this in
--  arrive with the Zuordnung module; the column is here because the card and the counters already ask for it.
alter table documents
    add column mandant_id uuid references contacts (id) on delete set null;

create index documents_mandant_idx on documents (tenant_id, mandant_id);

comment on column documents.mandant_id is
    'The client this voucher belongs to. Null means "Ohne Mandant": shown in the queue, never exported (§5.2).';
