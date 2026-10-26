-- Versioned SQL checksums do not encode resolved placeholder values.
-- Prevent model/dimension drift against a retained database before any DDL.
DO $$
BEGIN
    IF TO_REGCLASS(FORMAT('%I.rag_vector_configuration', CURRENT_SCHEMA())) IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM rag_vector_configuration
            WHERE id = 1 AND dimensions = ${ragVectorDimensions} AND model_id = '${ragEmbeddingModel}'
        ) THEN
            RAISE EXCEPTION 'Vector space mismatch; an explicit reindex/schema migration is required';
        END IF;
    END IF;
END $$;
