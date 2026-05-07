ALTER TABLE context
    ADD COLUMN IF NOT EXISTS active_window_start_message_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_messages_session_created_at
    ON messages (session_id, created_at);

COMMENT ON COLUMN context.active_window_start_message_id IS '活跃窗口起始消息ID，用于替代大 active_messages JSON 列表';
