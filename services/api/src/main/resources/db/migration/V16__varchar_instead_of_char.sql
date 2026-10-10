-- =====================================================================================
-- V16 — Replace fixed length `character(n)` columns with `varchar(n)`
--
-- Why: PostgreSQL pads `char(n)` values with blanks, so a hash stored in `char(64)` is returned as
-- 64 characters and compares equal to the same hash — but it compares *unequal* to a shorter
-- trimmed value, and Hibernate's schema validation reports a mismatch between `bpchar` and
-- `varchar`. Fixed length types therefore create two classes of surprises: silent padding in
-- comparisons and a permanent friction between the schema and the entity mappings. `varchar(n)`
-- enforces the same maximum length without the padding semantics.
--
-- The CHECK constraints that validate the format of hashes, country codes and currencies are not
-- affected: they keep working on the converted columns.
-- =====================================================================================

DO $$
DECLARE
    target record;
    converted integer := 0;
BEGIN
    FOR target IN
        SELECT table_name, column_name, character_maximum_length
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type = 'character'
          AND character_maximum_length IS NOT NULL
        ORDER BY table_name, column_name
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE varchar(%s)',
                       target.table_name, target.column_name, target.character_maximum_length);
        converted := converted + 1;
    END LOOP;
    RAISE NOTICE 'Converted % fixed length column(s) to varchar', converted;
END $$;
