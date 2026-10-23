-- =============================================================================
--  V88 - the payments and banking modules are gone (Kanzlei edition, §4.3)
-- =============================================================================
--  §4.3 removes both: partial payments belong to the bank/DATEV reconciliation a
--  Kanzlei does elsewhere, and banking integrations are P2/never in this edition.
--  The code is gone in this change; before writing the drops every remaining
--  reader of these tables was checked with rg. The one shared concept that had
--  to go with them is the amount-based authority matrix: it lived in
--  payment_approval_rules and only the payment approval flow ever configured it.
--  The retained approvals module now gives every request one deciding person.
--
--  supplier_bank_accounts: no Java reader outside the deleted banking module and
--  no reader in the web client - the invoice flag bank_details_changed is its
--  own column on invoices, not a lookup into this table. Dropped with banking.
--
--  financial_accounts is deliberately not dropped: it is the finance data model's
--  account register (spec §19) and not owned by the banking module alone.
-- =============================================================================

drop table if exists payment_matches cascade;
drop table if exists payment_approvals cascade;
drop table if exists payment_approval_rules cascade;
drop table if exists bank_transactions cascade;
drop table if exists bank_statements cascade;
drop table if exists payments cascade;
drop table if exists supplier_bank_accounts cascade;
