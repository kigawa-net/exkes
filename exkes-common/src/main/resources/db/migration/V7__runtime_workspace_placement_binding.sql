-- V7: bind each new Runtime to the specific verified Workspace/PVC identity (#50).
-- Legacy rows stay NULL: they cannot be retroactively bound by controller config.
-- Do not backfill without an operator-verified migration/termination plan.
ALTER TABLE runtimes ADD COLUMN workspace_placement_generation BIGINT NULL;
ALTER TABLE runtimes ADD COLUMN workspace_pvc_namespace VARCHAR(63) NULL;
ALTER TABLE runtimes ADD COLUMN workspace_pvc_name VARCHAR(128) NULL;
ALTER TABLE runtimes ADD COLUMN workspace_pvc_uid VARCHAR(64) NULL;

CREATE INDEX idx_runtimes_workspace_placement
    ON runtimes (workspace_id, workspace_placement_generation);
