-- executions: Runtime 内で実行される 1 つの処理。
-- Source of Truth は MariaDB。

CREATE TABLE executions (
    id                         VARCHAR(36)  NOT NULL,
    runtime_id                 VARCHAR(36)  NOT NULL,
    type                       VARCHAR(32)  NOT NULL,
    status                     VARCHAR(32)  NOT NULL,
    command_json               TEXT         NULL,
    agent_config_json          TEXT         NULL,
    exit_code                  INT          NULL,
    error_message              TEXT         NULL,
    log_object_key             VARCHAR(256) NULL,
    artifact_object_keys_json  TEXT         NULL,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at                 TIMESTAMP    NULL,
    finished_at                TIMESTAMP    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_executions_runtime FOREIGN KEY (runtime_id) REFERENCES runtimes(id)
);

CREATE INDEX idx_executions_runtime ON executions (runtime_id);
CREATE INDEX idx_executions_status  ON executions (status);