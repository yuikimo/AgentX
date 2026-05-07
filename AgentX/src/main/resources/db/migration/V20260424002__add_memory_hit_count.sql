-- 记忆热度管理：记录召回命中次数，用于排序、展示与容量淘汰
ALTER TABLE public.memory_items ADD COLUMN IF NOT EXISTS hit_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_memory_items_user_status_importance_hit
    ON public.memory_items (user_id, status, importance ASC, hit_count ASC, last_hit_at ASC, updated_at ASC)
    WHERE deleted_at IS NULL;
