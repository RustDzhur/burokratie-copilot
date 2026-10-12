-- The last eight tables, and the first case where the policy is not the same for all of them.
--
-- Every earlier migration used tenant_id = current_tenant_id() because no such table had a row without a
-- tenant. These do, and the counts decided the shape rather than a convention:
--
--   case_types               10 global of 10    - the catalogue is entirely global
--   document_type_rules      63 global of 477
--   roles                    10 global of 6883  - the system roles
--   system_settings          10 global of 545
--   deadline_rules            0 global of 670
--   payment_approval_rules    0 global of 1460
--   tax_rates                 0 global of 0
--   system_setting_history    0 global of 0
--
-- For the first four the policy must let a row with no tenant through, or the system roles and the global
-- catalogue disappear for every account. roles is the dangerous one: hiding it would break every permission
-- lookup, which is every request.

alter table case_types enable row level security;
alter table case_types force row level security;
create policy case_types_tenant_isolation on case_types
    using (tenant_id is null or tenant_id = current_tenant_id())
    with check (tenant_id is null or tenant_id = current_tenant_id());

alter table document_type_rules enable row level security;
alter table document_type_rules force row level security;
create policy document_type_rules_tenant_isolation on document_type_rules
    using (tenant_id is null or tenant_id = current_tenant_id())
    with check (tenant_id is null or tenant_id = current_tenant_id());

alter table roles enable row level security;
alter table roles force row level security;
create policy roles_tenant_isolation on roles
    using (tenant_id is null or tenant_id = current_tenant_id())
    with check (tenant_id is null or tenant_id = current_tenant_id());

alter table system_settings enable row level security;
alter table system_settings force row level security;
create policy system_settings_tenant_isolation on system_settings
    using (tenant_id is null or tenant_id = current_tenant_id())
    with check (tenant_id is null or tenant_id = current_tenant_id());

alter table deadline_rules enable row level security;
alter table deadline_rules force row level security;
create policy deadline_rules_tenant_isolation on deadline_rules
    using (tenant_id = current_tenant_id())
    with check (tenant_id = current_tenant_id());

alter table payment_approval_rules enable row level security;
alter table payment_approval_rules force row level security;
create policy payment_approval_rules_tenant_isolation on payment_approval_rules
    using (tenant_id = current_tenant_id())
    with check (tenant_id = current_tenant_id());

alter table tax_rates enable row level security;
alter table tax_rates force row level security;
create policy tax_rates_tenant_isolation on tax_rates
    using (tenant_id = current_tenant_id())
    with check (tenant_id = current_tenant_id());

alter table system_setting_history enable row level security;
alter table system_setting_history force row level security;
create policy system_setting_history_tenant_isolation on system_setting_history
    using (tenant_id = current_tenant_id())
    with check (tenant_id = current_tenant_id());
