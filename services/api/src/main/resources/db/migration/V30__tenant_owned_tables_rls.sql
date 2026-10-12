-- Six more tables carry a tenant_id of their own and had no row-level security.
--
--   document_templates     the letters an account writes
--   template_versions      their versions, with the wording
--   workflow_definitions   the processes an account has defined
--   workflow_versions      their published definitions
--   outbox_consumptions    what has already been consumed from the outbox
--   feature_flags          which features an account has
--
-- Each was checked for rows with tenant_id IS NULL before the policy was written: there are none, so
-- tenant_id = current_tenant_id() is the whole rule and no global row is hidden by it. That check matters -
-- roles carries tenant_id AND is_system, and the same naive policy there would hide the system roles and
-- break every account.

alter table document_templates enable row level security;
alter table document_templates force row level security;
create policy document_templates_tenant_isolation on document_templates
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());

alter table template_versions enable row level security;
alter table template_versions force row level security;
create policy template_versions_tenant_isolation on template_versions
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());

alter table workflow_definitions enable row level security;
alter table workflow_definitions force row level security;
create policy workflow_definitions_tenant_isolation on workflow_definitions
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());

alter table workflow_versions enable row level security;
alter table workflow_versions force row level security;
create policy workflow_versions_tenant_isolation on workflow_versions
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());

alter table outbox_consumptions enable row level security;
alter table outbox_consumptions force row level security;
create policy outbox_consumptions_tenant_isolation on outbox_consumptions
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());

alter table feature_flags enable row level security;
alter table feature_flags force row level security;
create policy feature_flags_tenant_isolation on feature_flags
    using (tenant_id = current_tenant_id()) with check (tenant_id = current_tenant_id());
