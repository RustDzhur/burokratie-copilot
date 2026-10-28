-- =====================================================================================
-- V14 — Optional semantic search (pgvector)
--
-- The specification allows either pgvector or a separate vector store adapter (§2.1,
-- §10.5). pgvector is not available in every deployment (see Q-06 in
-- docs/implementation-plan.md), therefore this migration is *conditional*: when the
-- extension cannot be installed, the system degrades to PostgreSQL full-text search and
-- reports semantic search as unavailable instead of pretending it works.
--
-- Two operational notes:
--  1. `CREATE EXTENSION vector` requires superuser privileges, because pgvector is not a
--     trusted extension. A database administrator has to run it once per database.
--     Without it this migration only emits a NOTICE and the platform stays fully usable.
--  2. The embedding column deliberately has NO fixed dimension: the dimension depends on the
--     embedding model, which is a configuration decision made by the operator. pgvector
--     requires a fixed dimension for an HNSW/IVFFlat index, so the index is NOT created here
--     — creating it with a guessed dimension would either be wrong or break the migration.
--     The exact statement to run after choosing a model is documented below and in
--     docs/data-model.md.
-- =====================================================================================

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
    RAISE NOTICE 'pgvector is available: semantic search is enabled';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector is NOT available (%): semantic search will be reported as unavailable', SQLERRM;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN

        -- One row per embedded chunk of a document page. `embedding_dimensions` records
        -- the model dimension so a model change can be detected instead of silently
        -- mixing incompatible vectors.
        EXECUTE $ddl$
            CREATE TABLE document_embeddings (
                id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                document_id       uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
                version_id        uuid REFERENCES document_versions (id) ON DELETE CASCADE,
                page_number       integer,
                chunk_index       integer NOT NULL DEFAULT 0,
                chunk_text        text NOT NULL,
                -- Dimension is intentionally unspecified; see the note at the top of this file.
                embedding         vector NOT NULL,
                embedding_model   varchar(120) NOT NULL,
                embedding_dimensions integer NOT NULL,
                created_at        timestamptz NOT NULL DEFAULT now(),
                CONSTRAINT document_embeddings_chunk_chk CHECK (chunk_index >= 0),
                CONSTRAINT document_embeddings_dims_chk CHECK (embedding_dimensions > 0),
                CONSTRAINT document_embeddings_uq UNIQUE (document_id, version_id, page_number, chunk_index, embedding_model)
            )
        $ddl$;

        EXECUTE 'CREATE INDEX document_embeddings_tenant_idx ON document_embeddings (tenant_id, document_id)';
        EXECUTE 'CREATE INDEX document_embeddings_model_idx ON document_embeddings (tenant_id, embedding_model)';
        -- No approximate-nearest-neighbour index yet: pgvector needs a fixed dimension for it.
        -- After the embedding model is configured, create it with, for example:
        --   ALTER TABLE document_embeddings
        --       ALTER COLUMN embedding TYPE vector(1536) USING embedding::vector(1536);
        --   CREATE INDEX document_embeddings_hnsw_idx
        --       ON document_embeddings USING hnsw (embedding vector_cosine_ops);
        -- Until then similarity search runs as an exact scan, which is correct and predictable.

        EXECUTE 'ALTER TABLE document_embeddings ENABLE ROW LEVEL SECURITY';
        EXECUTE 'ALTER TABLE document_embeddings FORCE ROW LEVEL SECURITY';
        EXECUTE $policy$
            CREATE POLICY document_embeddings_tenant_isolation ON document_embeddings
            USING (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
            WITH CHECK (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
        $policy$;

        RAISE NOTICE 'document_embeddings created';
    ELSE
        RAISE NOTICE 'document_embeddings skipped: pgvector missing';
    END IF;
END $$;
