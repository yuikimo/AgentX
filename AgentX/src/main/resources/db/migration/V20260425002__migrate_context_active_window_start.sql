UPDATE context
SET active_window_start_message_id = NULLIF(active_messages ->> 0, '')
WHERE (active_window_start_message_id IS NULL OR BTRIM(active_window_start_message_id) = '')
  AND active_messages IS NOT NULL
  AND jsonb_typeof(active_messages) = 'array'
  AND jsonb_array_length(active_messages) > 0;

UPDATE context
SET active_messages = NULL
WHERE active_messages IS NOT NULL;

COMMENT ON COLUMN context.active_messages IS '历史遗留字段，已由 active_window_start_message_id 取代';
