-- 记忆生命周期管理：添加 last_hit_at 字段，记录记忆最后一次被召回命中的时间
ALTER TABLE public.memory_items ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMP;

-- 为过期淘汰查询添加索引
CREATE INDEX IF NOT EXISTS idx_memory_items_user_type_status
    ON public.memory_items (user_id, type, status)
    WHERE deleted_at IS NULL;
