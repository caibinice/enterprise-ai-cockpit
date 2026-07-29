ALTER TABLE knowledge_bases
    ADD COLUMN business_type VARCHAR(100) NOT NULL DEFAULT '通用业务' AFTER code;
