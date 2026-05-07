CREATE INDEX IF NOT EXISTS idx_messages_session_role_created_at
    ON messages (session_id, role, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sessions_agent_user_created_at
    ON sessions (agent_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_context_session_id
    ON context (session_id);
