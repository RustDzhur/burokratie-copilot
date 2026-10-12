-- Three more tables that reach their tenant through a document, and had no row-level security.
--
-- V28 fixed document_extractions and document_classifications after a test asked the second account what it
-- could see. The same audit, run over every table rather than the ones I happened to remember, found these:
--
--   document_pages              the page text of a document
--   document_processing_steps   what the pipeline did, with its intermediate results
--   document_tag_links          which tags a document carries
--
-- document_pages is the most sensitive of the three: it is the text itself. A caller who can name a document
-- identifier could read the contents of somebody else's letter page by page.
--
-- The policy reaches the tenant the same way, because these rows have no tenant_id of their own.

alter table document_pages enable row level security;
alter table document_pages force row level security;
create policy document_pages_tenant_isolation on document_pages
    using (exists (select 1 from documents d where d.id = document_pages.document_id
                     and d.tenant_id = current_tenant_id()))
    with check (exists (select 1 from documents d where d.id = document_pages.document_id
                          and d.tenant_id = current_tenant_id()));

alter table document_processing_steps enable row level security;
alter table document_processing_steps force row level security;
create policy document_processing_steps_tenant_isolation on document_processing_steps
    using (exists (select 1 from documents d where d.id = document_processing_steps.document_id
                     and d.tenant_id = current_tenant_id()))
    with check (exists (select 1 from documents d where d.id = document_processing_steps.document_id
                          and d.tenant_id = current_tenant_id()));

alter table document_tag_links enable row level security;
alter table document_tag_links force row level security;
create policy document_tag_links_tenant_isolation on document_tag_links
    using (exists (select 1 from documents d where d.id = document_tag_links.document_id
                     and d.tenant_id = current_tenant_id()))
    with check (exists (select 1 from documents d where d.id = document_tag_links.document_id
                          and d.tenant_id = current_tenant_id()));
