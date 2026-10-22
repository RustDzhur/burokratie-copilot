-- =============================================================================
--  V74 - the parties module is gone (Kanzlei edition, §4.3)
-- =============================================================================
--  Everything that read parties now reads contacts: the addressee matcher, the case summary, the telegram digest,
--  the document card and the invoice name. The merge that created those contacts is V73, and parties_contact_map
--  stays behind on purpose - it is the record of how each party was matched, and a wrong match should be findable
--  long after the table it came from is gone.
-- =============================================================================

-- Documents no longer carry a second client field. The person-facing assignment writes mandant_id.
alter table documents drop column if exists party_id;

-- The module's own table.
drop table if exists parties cascade;

comment on table parties_contact_map is
    'How each former party became a contact (V73). Kept after V74 removed the parties table.';
