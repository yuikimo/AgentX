ALTER TABLE document_unit
    ADD COLUMN IF NOT EXISTS chunk_index INTEGER;

CREATE INDEX IF NOT EXISTS idx_document_unit_file_chunk_vector
    ON document_unit (file_id, chunk_index)
    WHERE deleted_at IS NULL AND is_vector = true;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY file_id
               ORDER BY page NULLS LAST, created_at NULLS LAST, id
           ) - 1 AS seq_chunk_index
    FROM document_unit
    WHERE deleted_at IS NULL
)
UPDATE document_unit du
SET chunk_index = ranked.seq_chunk_index
FROM ranked
WHERE du.id = ranked.id
  AND du.chunk_index IS NULL;
