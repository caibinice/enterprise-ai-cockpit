-- Enterprise AI Cockpit application database.
-- Compatible with MySQL 8 and MariaDB 10.5+.
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    code VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_bases_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(500) NOT NULL,
    content LONGTEXT NOT NULL,
    metadata LONGTEXT NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_documents_kb_created (knowledge_base_id, created_at),
    CONSTRAINT fk_documents_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(500) NOT NULL,
    content LONGTEXT NOT NULL,
    metadata LONGTEXT NOT NULL,
    chunk_order INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_chunks_kb (knowledge_base_id),
    KEY idx_chunks_document_order (document_id, chunk_order),
    CONSTRAINT fk_chunks_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS data_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(100) NOT NULL,
    endpoint VARCHAR(1000) NOT NULL DEFAULT '',
    query_text TEXT NOT NULL,
    config LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS report_templates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    schedule_type VARCHAR(50) NOT NULL,
    cron VARCHAR(200) NOT NULL DEFAULT '',
    data_source_key VARCHAR(200) NOT NULL DEFAULT '',
    knowledge_base_id BIGINT NOT NULL DEFAULT 0,
    prompt TEXT NOT NULL,
    dimensions VARCHAR(1000) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_report_templates_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS report_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(50) NOT NULL,
    summary LONGTEXT NOT NULL,
    metrics_json LONGTEXT NOT NULL,
    chart_spec LONGTEXT NOT NULL,
    logs LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_report_runs_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_chat_messages_conversation (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
