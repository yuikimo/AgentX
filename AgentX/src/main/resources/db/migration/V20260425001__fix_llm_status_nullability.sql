-- 修复历史数据中 status 为空导致的空指针问题
UPDATE models
SET status = TRUE
WHERE status IS NULL;

UPDATE providers
SET status = TRUE
WHERE status IS NULL;

-- 约束后续数据，避免再次写入 NULL
ALTER TABLE models
ALTER COLUMN status SET DEFAULT TRUE;

ALTER TABLE models
ALTER COLUMN status SET NOT NULL;

ALTER TABLE providers
ALTER COLUMN status SET DEFAULT TRUE;

ALTER TABLE providers
ALTER COLUMN status SET NOT NULL;
