CREATE INDEX IF NOT EXISTS idx_memory_items_user_status_updated_at
    ON public.memory_items (user_id, status, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_memory_items_user_dedupe_hash
    ON public.memory_items (user_id, dedupe_hash)
    WHERE deleted_at IS NULL;
