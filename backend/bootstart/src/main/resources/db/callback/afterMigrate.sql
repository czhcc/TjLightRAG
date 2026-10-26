-- Optional well-known runtime role is provisioned outside application migrations.
-- Grant DML only; schema and migration ownership stay with the migrator.
DO $$
DECLARE
    target_schema TEXT := CURRENT_SCHEMA();
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tjlightrag_app') THEN
        EXECUTE FORMAT('GRANT USAGE ON SCHEMA %I TO tjlightrag_app', target_schema);
        EXECUTE FORMAT('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO tjlightrag_app', target_schema);
        EXECUTE FORMAT('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO tjlightrag_app', target_schema);
        EXECUTE FORMAT('REVOKE INSERT, UPDATE, DELETE ON TABLE %I.flyway_schema_history FROM tjlightrag_app', target_schema);
        IF TO_REGCLASS(FORMAT('%I.rag_vector_configuration', target_schema)) IS NOT NULL THEN
            EXECUTE FORMAT('REVOKE INSERT, UPDATE, DELETE ON TABLE %I.rag_vector_configuration FROM tjlightrag_app', target_schema);
        END IF;
    END IF;
END $$;
