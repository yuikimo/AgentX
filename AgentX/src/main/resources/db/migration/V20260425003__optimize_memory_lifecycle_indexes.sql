CREATE INDEX IF NOT EXISTS idx_memory_items_cleanup_status_type_updated_at
    ON public.memory_items (status, type, updated_at ASC)
    WHERE deleted_at IS NULL;
