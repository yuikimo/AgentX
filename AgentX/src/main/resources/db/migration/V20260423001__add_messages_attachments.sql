ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS attachments JSONB DEFAULT '[]'::jsonb;

COMMENT ON COLUMN messages.attachments IS '消息附件详情，包含url/name/contentType/kind/summary';
