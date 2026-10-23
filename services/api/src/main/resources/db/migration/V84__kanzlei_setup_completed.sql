-- =============================================================================
--  V84 - the first-run wizard of §6.1 has a finish, and the cabinet remembers it
-- =============================================================================
--  §6.1 replaces the old wizard with three steps: the name of the office, its clients, the first
--  documents. Finishing it is a property of the cabinet, not of one person: a colleague who is
--  invited later must not be sent through the wizard again. The old answer lived in
--  user_preferences under the key 'onboarding', per person - it is carried over here and removed,
--  so that a cabinet which already finished the wizard is not asked a second time.
--
--  What is NOT dropped: user_preferences itself. It still holds accessibility settings that have
--  nothing to do with the wizard, and the table is not ours to remove for one obsolete key.
-- =============================================================================

alter table tenants add column if not exists setup_completed_at timestamptz;

comment on column tenants.setup_completed_at is
    'When the first-run wizard (§6.1) was finished for this cabinet; null until then.';

-- Carry the answer over from the per-person preference the old wizard wrote. The earliest
-- completion is the one that counts: the cabinet was set up when the first person finished it.
update tenants t
set setup_completed_at = completed.finished_at
from (
    select m.tenant_id, min(p.updated_at) as finished_at
    from user_preferences p
    join memberships m on m.user_id = p.user_id
    where p.pref_key = 'onboarding'
    group by m.tenant_id
) completed
where completed.tenant_id = t.id
  and t.setup_completed_at is null;

-- The key is no longer written or read by any code; leaving it would be data nobody understands.
delete from user_preferences where pref_key = 'onboarding';
