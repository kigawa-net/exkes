-- V4: POST の Idempotency-Key（同一キーの再送を同一リソースへ束ねる）と Execution の labels。
-- V1〜V3 は変更しない。

-- scope は操作ごとの名前空間（workspaces / runtimes:<workspaceId> / executions:<runtimeId>）。
-- 同一キーでも要求内容（request_digest）が違えば API 層で拒否する。
CREATE TABLE idempotency_keys (
    scope            VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_digest   VARCHAR(64)  NOT NULL,            -- 正規化した要求内容の SHA-256（hex）
    resource_id      VARCHAR(36)  NOT NULL,            -- 最初の要求で作成されたリソースの id
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope, idempotency_key)
);

-- executions.labels_json は応答用（Runtime と同じ JSON 形式）。
-- 一覧の label フィルタは execution_labels で行う。
ALTER TABLE executions ADD COLUMN labels_json TEXT NULL;

CREATE TABLE execution_labels (
    execution_id  VARCHAR(36)  NOT NULL,
    label_key     VARCHAR(63)  NOT NULL,
    label_value   VARCHAR(256) NOT NULL,
    PRIMARY KEY (execution_id, label_key),
    CONSTRAINT fk_execution_labels_execution FOREIGN KEY (execution_id) REFERENCES executions(id)
);

CREATE INDEX idx_execution_labels_kv ON execution_labels (label_key, label_value);
