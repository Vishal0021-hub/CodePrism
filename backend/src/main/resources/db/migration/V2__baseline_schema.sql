-- V2: Baseline schema for all application tables.
-- This migration is derived from the Hibernate entity model and reflects the
-- schema that ddl-auto=update would produce on a fresh database.
-- Every future migration must ADD before it REMOVES (additive-first rule).

-- ── users ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id       BIGINT  NOT NULL UNIQUE,
    github_username VARCHAR(100) NOT NULL,
    display_name    VARCHAR(200) NOT NULL,
    avatar_url      VARCHAR(500),
    access_token    TEXT    NOT NULL,
    token_scopes    VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── repositories ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS repositories (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID    NOT NULL,
    github_repo_id          BIGINT  NOT NULL,
    owner                   VARCHAR(100) NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    full_name               VARCHAR(300) NOT NULL,
    is_private              BOOLEAN NOT NULL DEFAULT FALSE,
    default_branch          VARCHAR(100) NOT NULL,
    language                VARCHAR(100),
    html_url                VARCHAR(500),
    description             TEXT,
    index_status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    indexed_at              TIMESTAMPTZ,
    chunk_count             INT     NOT NULL DEFAULT 0,
    files_total             INT     NOT NULL DEFAULT 0,
    files_processed         INT     NOT NULL DEFAULT 0,
    error_message           TEXT,
    last_indexed_commit_sha VARCHAR(100),
    auto_sync               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_repo_user_github UNIQUE (user_id, github_repo_id)
);

CREATE INDEX IF NOT EXISTS idx_repositories_user_id ON repositories (user_id);
CREATE INDEX IF NOT EXISTS idx_repositories_full_name ON repositories (full_name);

-- ── chat_sessions ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID    NOT NULL,
    repository_id   UUID    NOT NULL,
    title           VARCHAR(200) NOT NULL DEFAULT 'New chat',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_repo
    ON chat_sessions (user_id, repository_id);

-- ── chat_messages ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID    NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT    NOT NULL,
    citations       TEXT,
    sources_count   INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages (session_id);

-- ── code_relationships ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS code_relationships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id   UUID        NOT NULL,
    source_symbol   VARCHAR(300) NOT NULL,
    target_symbol   VARCHAR(300) NOT NULL,
    relation_type   VARCHAR(50)  NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_code_rel_repo_source
    ON code_relationships (repository_id, source_symbol);
CREATE INDEX IF NOT EXISTS idx_code_rel_repo_target
    ON code_relationships (repository_id, target_symbol);
CREATE INDEX IF NOT EXISTS idx_code_rel_repo_file
    ON code_relationships (repository_id, file_path);

-- ── vector_store (Spring AI PgVectorStore) ────────────────────────────────
-- Spring AI PgVectorStore creates this table itself when initialize-schema=true.
-- In production (initialize-schema=false), Flyway owns it.
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID       PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(3072)
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);
