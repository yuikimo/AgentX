ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS original_provider_id VARCHAR(64);

COMMENT ON COLUMN messages.original_provider_id IS '原始 provider id，用于审计故障转移前后的服务商';
