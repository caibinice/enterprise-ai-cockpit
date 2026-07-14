CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS enterprise_ai_vectors (
    chunk_id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(500) NOT NULL, content TEXT NOT NULL, metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1536) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_enterprise_ai_vectors_kb ON enterprise_ai_vectors (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_enterprise_ai_vectors_embedding_cosine
    ON enterprise_ai_vectors USING ivfflat (embedding vector_cosine_ops) WITH (lists = 10);
