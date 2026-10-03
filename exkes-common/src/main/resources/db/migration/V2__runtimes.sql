-- runtimes: Workspace 上で実行される隔離環境。
-- Source of Truth は MariaDB。Pod はこの行を正として作成/削除する。
-- runtime_templates: Runtime の雛形（image/resources/default TTL 等）。

CREATE TABLE runtime_templates (
    id                      VARCHAR(64)  NOT NULL,
    name                    VARCHAR(128) NOT NULL,
    description             TEXT         NULL,
    image                   VARCHAR(256) NOT NULL,
    default_resources_json  TEXT         NULL,
    default_env_json        TEXT         NULL,
    default_ttl_seconds     BIGINT       NULL,
    labels_json             TEXT         NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE runtimes (
    id                      VARCHAR(36)  NOT NULL,
    workspace_id            VARCHAR(36)  NOT NULL,
    template_id             VARCHAR(64)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    resources_json          TEXT         NULL,
    env_json                TEXT         NULL,
    ttl_seconds             BIGINT       NULL,
    pod_name                VARCHAR(128) NULL,
    error_message           TEXT         NULL,
    labels_json             TEXT         NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_runtimes_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_runtimes_template  FOREIGN KEY (template_id) REFERENCES runtime_templates(id)
);

CREATE INDEX idx_runtimes_workspace ON runtimes (workspace_id);
CREATE INDEX idx_runtimes_status    ON runtimes (status);
CREATE INDEX idx_runtimes_ttl       ON runtimes (ttl_seconds, created_at);