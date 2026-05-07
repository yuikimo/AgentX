ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS title_renamed BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sessions.title_renamed IS '标题是否已完成命名（手动或智能）';
