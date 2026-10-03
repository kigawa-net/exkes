-- workspaces: Runtime より長いライフサイクルを持つ永続 filesystem の metadata。
-- Source of Truth は MariaDB。PVC はこの行を正として作成/削除する。

CREATE TABLE workspaces (
    id            VARCHAR(36)  NOT NULL,            -- UUID (java.util.UUID#toString)
    name          VARCHAR(128) NOT NULL,            -- 利用者指定の一意な名前
    status        VARCHAR(32)  NOT NULL,            -- CREATING/READY/ARCHIVED/DELETING/DELETED/ERROR
    storage_class VARCHAR(64)  NOT NULL DEFAULT 'rook-cephfs',
    storage_size  BIGINT       NOT NULL DEFAULT 1073741824,  -- bytes（既定 1Gi）
    pvc_name      VARCHAR(128) NULL,                -- 例: ws-<id>（CREATING 中は NULL）
    error_message TEXT         NULL,                -- status=ERROR の理由
    labels_json   TEXT         NULL,                -- 任意ラベル（kotlinx.serialization で JSON 化）
    archived_at   TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_workspaces_name UNIQUE (name)
);

CREATE INDEX idx_workspaces_status ON workspaces (status);
