-- =============================================================================
--  V83 - the household module is gone (Kanzlei edition, §4.3)
-- =============================================================================
--  §1 says the product has one user: the Kanzlei. §4.3 therefore deletes the family module and the
--  private/family mode. The code (FamilyAccessService, FamilyController) is removed in the same change;
--  this migration removes what only that code wrote and normalises the rows that still name a cabinet
--  kind the product no longer has.
--
--  What is NOT dropped:
--    * tenants.account_type and workspaces.mode keep their columns and their CHECK constraints - they are
--      NOT NULL and every row needs a value. They are normalised, not removed.
--    * the FAMILY and PERSONAL rows in plans stay: subscriptions.plan_code references them (foreign key) and a
--      migration that ran is history. They are switched off below, so the API no longer offers them.
--    * plans.audiences and document_types.audiences stay as catalogue data; no code filters by them any
--      more, and dropping a column the catalogue still uses would be a separate change.
-- =============================================================================

-- 1. The two tables only the family module read. Verified before dropping: no main-code reader outside
--    `family/` (grep for personal_documents / document_shares), so the drop cannot break a screen.
drop table if exists document_shares;
drop table if exists personal_documents;

-- 2. One kind of cabinet. Existing private and household accounts become what the product now is - a
--    Kanzlei account. Their documents, memberships and subscriptions are untouched.
update tenants set account_type = 'COMPANY', updated_at = now()
 where account_type in ('PRIVATE_PERSON', 'FAMILY_HOUSEHOLD');

update workspaces set mode = 'BUSINESS', updated_at = now()
 where mode in ('PRIVATE', 'FAMILY');

-- 3. The tariffs of the removed kinds are no longer offered. The rows stay because subscriptions.plan_code
--    references them; a subscription that already paid for one keeps it.
update plans set is_active = false where code in ('PERSONAL', 'FAMILY');
