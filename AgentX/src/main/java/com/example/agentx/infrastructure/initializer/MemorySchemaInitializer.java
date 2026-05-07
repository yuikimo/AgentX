package com.example.agentx.infrastructure.initializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(10)
public class MemorySchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemorySchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public MemorySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureMemorySchema();
            log.info("memory 相关表结构检查完成");
        } catch (Exception e) {
            log.error("memory 相关表结构检查失败", e);
        }
    }

    private void ensureMemorySchema() {
        List<String> sqlStatements = List.of(
                """
                        CREATE TABLE IF NOT EXISTS public.memory_items (
                            id VARCHAR(64) PRIMARY KEY,
                            user_id VARCHAR(64) NOT NULL,
                            type VARCHAR(16) NOT NULL,
                            text TEXT NOT NULL,
                            data JSONB,
                            importance REAL NOT NULL DEFAULT 0.5,
                            tags JSONB DEFAULT '[]'::jsonb,
                            source_session_id VARCHAR(64),
                            scope_agent_id VARCHAR(64),
                            dedupe_hash VARCHAR(128),
                            status SMALLINT NOT NULL DEFAULT 1,
                            last_hit_at TIMESTAMP WITHOUT TIME ZONE,
                            hit_count INTEGER NOT NULL DEFAULT 0,
                            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            deleted_at TIMESTAMP WITHOUT TIME ZONE
                        )
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_user_status_updated_at
                            ON public.memory_items (user_id, status, updated_at DESC)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_user_dedupe_hash
                            ON public.memory_items (user_id, dedupe_hash)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_user_type_status
                            ON public.memory_items (user_id, type, status)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_cleanup_status_type_updated_at
                            ON public.memory_items (status, type, updated_at ASC)
                            WHERE deleted_at IS NULL
                        """,
                """
                        ALTER TABLE public.memory_items
                            ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMP WITHOUT TIME ZONE
                        """,
                """
                        ALTER TABLE public.memory_items
                            ADD COLUMN IF NOT EXISTS scope_agent_id VARCHAR(64)
                        """,
                """
                        ALTER TABLE public.memory_items
                            ADD COLUMN IF NOT EXISTS hit_count INTEGER NOT NULL DEFAULT 0
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_user_scope_status
                            ON public.memory_items (user_id, scope_agent_id, status)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_items_user_status_importance_hit
                            ON public.memory_items (user_id, status, importance ASC, hit_count ASC, last_hit_at ASC, updated_at ASC)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE TABLE IF NOT EXISTS public.memory_pending_candidates (
                            id VARCHAR(64) PRIMARY KEY,
                            user_id VARCHAR(64) NOT NULL,
                            scope_agent_id VARCHAR(64),
                            source_session_id VARCHAR(64),
                            type VARCHAR(16) NOT NULL,
                            text TEXT NOT NULL,
                            importance REAL NOT NULL DEFAULT 0.5,
                            tags JSONB DEFAULT '[]'::jsonb,
                            dedupe_hash VARCHAR(128) NOT NULL,
                            seen_count INTEGER NOT NULL DEFAULT 1,
                            status SMALLINT NOT NULL DEFAULT 1,
                            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            deleted_at TIMESTAMP WITHOUT TIME ZONE
                        )
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_pending_candidates_user_status_updated_at
                            ON public.memory_pending_candidates (user_id, status, updated_at DESC)
                            WHERE deleted_at IS NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_memory_pending_candidates_user_scope_hash
                            ON public.memory_pending_candidates (user_id, scope_agent_id, dedupe_hash)
                            WHERE deleted_at IS NULL
                        """
        );

        for (String sql : sqlStatements) {
            jdbcTemplate.execute(sql);
        }
    }
}
