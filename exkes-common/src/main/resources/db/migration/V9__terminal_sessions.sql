-- V9: terminal_sessions (plan chapter 5.1 / 6.x, 第5MS).
-- A session is a single-use, short-lived credential that exkes-terminal-gateway
-- consumes to open a Terminal to a Runtime. The plaintext token is returned to the
-- caller exactly once at issuance and is NEVER stored: only its SHA-256 (hex) is.
-- Renamed from the plan's "V6": V6 is already used by workspace placement identity.

CREATE TABLE terminal_sessions (
    id              VARCHAR(36)  NOT NULL,
    runtime_id      VARCHAR(36)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,              -- hex SHA-256 of the plaintext token
    status          VARCHAR(16)  NOT NULL,              -- ISSUED / CONSUMED / EXPIRED
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_terminal_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_terminal_sessions_runtime FOREIGN KEY (runtime_id) REFERENCES runtimes(id),
    CONSTRAINT ck_terminal_sessions_status CHECK (status IN ('ISSUED', 'CONSUMED', 'EXPIRED'))
);

CREATE INDEX idx_terminal_sessions_runtime ON terminal_sessions (runtime_id, status);
CREATE INDEX idx_terminal_sessions_expiry ON terminal_sessions (status, expires_at);
