ALTER TABLE public.memory_items
    ADD COLUMN IF NOT EXISTS scope_agent_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_memory_items_user_scope_status
    ON public.memory_items (user_id, scope_agent_id, status)
    WHERE deleted_at IS NULL;
