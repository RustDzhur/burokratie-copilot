-- =============================================================================
--  V75 - the memory and radar modules are gone (Kanzlei edition, §4.3)
-- =============================================================================
--  §4.3 removes both as an AI cluster without a working AI. The code is gone in this commit; these tables had no
--  reader left, which was checked with rg before writing the drop rather than assumed from the module names.
--
--  contract_obligations is deliberately NOT here: it belongs to the contracts module, which is removed in its own
--  step once its remaining SQL readers are dealt with.
-- =============================================================================

drop table if exists fact_revisions cascade;
drop table if exists contact_relations cascade;
drop table if exists contact_profiles cascade;
drop table if exists radar_signals cascade;
