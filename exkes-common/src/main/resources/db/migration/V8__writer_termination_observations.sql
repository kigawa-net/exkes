-- V8: immutable, non-authoritative evidence observations for #60.
-- This table intentionally cannot certify a Writer terminated or unlock PVC DELETE.
-- Verified receipts, issuer authentication and deletion-fence CAS are separate follow-ups.
CREATE TABLE writer_termination_observations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    workspace_placement_generation BIGINT NOT NULL,
    workspace_pvc_uid VARCHAR(64) NOT NULL,
    runtime_id VARCHAR(36) NOT NULL,
    pod_uid VARCHAR(64) NULL,
    writer_generation BIGINT NULL,
    evidence_source VARCHAR(32) NOT NULL,
    evidence_kind VARCHAR(32) NOT NULL,
    observation_status VARCHAR(16) NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_wto_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_wto_runtime FOREIGN KEY (runtime_id) REFERENCES runtimes(id),
    CONSTRAINT ck_wto_status CHECK (observation_status IN ('UNKNOWN', 'CONFLICT')),
    CONSTRAINT uk_wto_runtime_kind_digest UNIQUE (runtime_id, evidence_kind, evidence_digest)
);

CREATE INDEX idx_wto_workspace_generation
    ON writer_termination_observations (workspace_id, workspace_placement_generation, created_at);
CREATE INDEX idx_wto_runtime_created
    ON writer_termination_observations (runtime_id, created_at);
