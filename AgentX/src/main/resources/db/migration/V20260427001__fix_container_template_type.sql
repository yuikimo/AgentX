UPDATE container_templates
SET type = 'USER',
    updated_at = CURRENT_TIMESTAMP
WHERE type = 'mcp-gateway';
