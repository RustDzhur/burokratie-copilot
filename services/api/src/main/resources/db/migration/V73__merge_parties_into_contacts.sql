-- =============================================================================
--  V73 - parties become contacts (Kanzlei edition, §4.3)
-- =============================================================================
--  §4.3 says parties is "merged with contacts -> Mandanten", and §8.3 names documents.mandant_id as the foreign
--  key to contacts. Two tables answering "who is this document about" is how a screen and an export start
--  disagreeing, so this migration moves the data and keeps the map.
--
--  It deliberately does NOT drop anything. The old column and table stay until the reader and the writer are
--  moved over (see docs/business/kanzlei-handoff.md §3.1); dropping first would have no way back.
--
--  Matching is by lowercased display name within one account. That is the only field the two tables share - the
--  parties identifiers are free-form and not reliably filled - and a wrong merge would be worse than a duplicate,
--  so the map is written down row by row and can be inspected.
-- =============================================================================

create table parties_contact_map (
    party_id   uuid primary key,
    contact_id uuid not null,
    how        text not null check (how in ('matched', 'created')),
    created_at timestamptz not null default now()
);

comment on table parties_contact_map is
    'Which contact each party became. Kept after the merge so a wrong match can be found and corrected.';

-- 1. A contact already exists with the same name: reuse it, do not create a second one.
insert into parties_contact_map (party_id, contact_id, how)
select p.id, c.id, 'matched'
  from parties p
  join contacts c
    on c.tenant_id = p.tenant_id
   and lower(c.display_name) = lower(p.display_name)
   and c.deleted_at is null
 where p.deleted_at is null
   and not exists (select 1 from parties_contact_map m where m.party_id = p.id);

-- 2. Everything else becomes a contact, carrying the only fields that mean the same thing.
insert into contacts (tenant_id, contact_type, display_name, notes, metadata, created_at)
select p.tenant_id,
       case when p.kind = 'ORGANIZATION' then 'ORGANIZATION' else 'PERSON' end,
       p.display_name,
       p.notes,
       jsonb_build_object('migratedFromParty', p.id::text),
       coalesce(p.created_at, now())
  from parties p
 where p.deleted_at is null
   and not exists (select 1 from parties_contact_map m where m.party_id = p.id);

insert into parties_contact_map (party_id, contact_id, how)
select p.id, c.id, 'created'
  from parties p
  join contacts c
    on c.tenant_id = p.tenant_id
   and c.metadata ->> 'migratedFromParty' = p.id::text
 where p.deleted_at is null
   and not exists (select 1 from parties_contact_map m where m.party_id = p.id);

-- 3. Documents that named a party now name the contact it became. Only where no client was set by a person:
--    a person's decision outranks a migration.
update documents d
   set mandant_id = m.contact_id
  from parties_contact_map m
 where d.party_id = m.party_id
   and d.mandant_id is null;

create index parties_contact_map_contact_idx on parties_contact_map (contact_id);
