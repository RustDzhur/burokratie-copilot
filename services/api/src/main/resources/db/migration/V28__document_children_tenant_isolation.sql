-- Two tables that hold what was taken out of a document had no row-level security at all.
--
-- documents, document_versions and document_blobs are all protected with FORCE ROW LEVEL SECURITY and a
-- policy on tenant_id. document_extractions and document_classifications were not: they carry no tenant_id
-- of their own - their scope comes through the document - and nobody had written the policy that says so.
--
-- The consequence was not a theoretical one. A caller cannot list another tenant's documents, because that
-- table is protected. But if they can name a document identifier, they can read its classifications and its
-- extracted values, and those hold the amounts, the invoice numbers and the reference of somebody else's
-- correspondence. A test found it by asking the second account what it could see.
--
-- The policy reaches the tenant through the document, because that is where the tenant of these rows lives.

alter table document_extractions enable row level security;
alter table document_extractions force row level security;

create policy document_extractions_tenant_isolation on document_extractions
    using (exists (
        select 1 from documents d
         where d.id = document_extractions.document_id
           and d.tenant_id = current_tenant_id()))
    with check (exists (
        select 1 from documents d
         where d.id = document_extractions.document_id
           and d.tenant_id = current_tenant_id()));

alter table document_classifications enable row level security;
alter table document_classifications force row level security;

create policy document_classifications_tenant_isolation on document_classifications
    using (exists (
        select 1 from documents d
         where d.id = document_classifications.document_id
           and d.tenant_id = current_tenant_id()))
    with check (exists (
        select 1 from documents d
         where d.id = document_classifications.document_id
           and d.tenant_id = current_tenant_id()));
