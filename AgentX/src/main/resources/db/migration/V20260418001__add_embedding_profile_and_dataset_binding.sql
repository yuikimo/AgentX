ALTER TABLE ai_rag_qa_dataset
    ADD COLUMN IF NOT EXISTS embedding_model_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS active_embedding_profile_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS pending_embedding_model_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS pending_embedding_profile_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS embedding_migration_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS embedding_migration_error TEXT;

CREATE INDEX IF NOT EXISTS idx_dataset_embedding_model_id ON ai_rag_qa_dataset (embedding_model_id);
CREATE INDEX IF NOT EXISTS idx_dataset_pending_embedding_model_id ON ai_rag_qa_dataset (pending_embedding_model_id);
CREATE INDEX IF NOT EXISTS idx_dataset_active_embedding_profile_id ON ai_rag_qa_dataset (active_embedding_profile_id);

CREATE TABLE IF NOT EXISTS embedding_profiles (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    model_id VARCHAR(36) NOT NULL,
    model_endpoint VARCHAR(255) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    dimension INTEGER NOT NULL,
    distance_metric VARCHAR(32) NOT NULL DEFAULT 'COSINE',
    table_name VARCHAR(128) NOT NULL,
    config_fingerprint VARCHAR(128) NOT NULL,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_profiles_user_fp
    ON embedding_profiles (user_id, config_fingerprint)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_profiles_table_name
    ON embedding_profiles (table_name)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_embedding_profiles_model_id ON embedding_profiles (model_id);

COMMENT ON TABLE embedding_profiles IS 'Embedding Profile，按模型配置+维度归一，用于数据集绑定与动态向量表路由';
COMMENT ON COLUMN embedding_profiles.id IS 'Profile ID（哈希）';
COMMENT ON COLUMN embedding_profiles.user_id IS '用户ID';
COMMENT ON COLUMN embedding_profiles.model_id IS '绑定的嵌入模型ID';
COMMENT ON COLUMN embedding_profiles.model_endpoint IS '模型端点';
COMMENT ON COLUMN embedding_profiles.base_url IS '模型Base URL';
COMMENT ON COLUMN embedding_profiles.dimension IS '嵌入维度';
COMMENT ON COLUMN embedding_profiles.distance_metric IS '向量距离度量（COSINE/L2/IP）';
COMMENT ON COLUMN embedding_profiles.table_name IS '该Profile对应的向量表名';
COMMENT ON COLUMN embedding_profiles.config_fingerprint IS '配置指纹（用于幂等创建）';

