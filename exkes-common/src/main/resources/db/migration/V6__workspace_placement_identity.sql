-- V6: Durable namespace/PVC identity metadata for Workspace placement (#39).
--
-- Deliberately additive. Existing Workspace rows remain UNBOUND even if a
-- controller already uses a sandbox namespace. Never infer ownership from the
-- current process environment during a Flyway migration.
ALTER TABLE workspaces ADD COLUMN pvc_namespace VARCHAR(63) NULL;
ALTER TABLE workspaces ADD COLUMN pvc_uid VARCHAR(64) NULL;
ALTER TABLE workspaces ADD COLUMN placement_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workspaces ADD COLUMN placement_state VARCHAR(32) NOT NULL DEFAULT 'UNBOUND';

CREATE INDEX idx_workspaces_placement_state ON workspaces (placement_state);
