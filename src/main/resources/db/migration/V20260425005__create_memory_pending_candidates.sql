CREATE TABLE IF NOT EXISTS public.memory_pending_candidates (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    scope_agent_id VARCHAR(64),
    source_session_id VARCHAR(64),
    type VARCHAR(16) NOT NULL,
    text TEXT NOT NULL,
    importance REAL NOT NULL DEFAULT 0.5,
    tags JSONB DEFAULT '[]'::jsonb,
    dedupe_hash VARCHAR(128) NOT NULL,
    seen_count INTEGER NOT NULL DEFAULT 1,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_memory_pending_candidates_user_status_updated_at
    ON public.memory_pending_candidates (user_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_memory_pending_candidates_user_scope_hash
    ON public.memory_pending_candidates (user_id, scope_agent_id, dedupe_hash)
    WHERE deleted_at IS NULL;
