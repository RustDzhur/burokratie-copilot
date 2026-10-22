-- =============================================================================
--  V76 - the contracts module is gone (Kanzlei edition, §4.3)
-- =============================================================================
--  Client contracts are the office's CRM or DATEV work, not this product's. The code is removed in this commit and
--  the SQL readers outside it went first: three lived in memory (V75), and the DSGVO export in privacy lost its
--  contract section in the same commit, because privacy stays and a legal export that queries a dropped table
--  fails at runtime where no compiler can see it.
-- =============================================================================

drop table if exists contract_events cascade;
drop table if exists contract_clauses cascade;
drop table if exists contract_versions cascade;
drop table if exists contract_obligations cascade;
drop table if exists contract_parties cascade;
drop table if exists contracts cascade;
