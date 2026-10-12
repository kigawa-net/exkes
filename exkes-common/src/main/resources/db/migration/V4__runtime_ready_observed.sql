-- runtimes.last_ready_observed_at: the last time exkes-controller observed the Pod Ready=True.
-- NULL means no fresh Ready observation: the controller clears it on the first NotReady
-- observation (admission closes at once, no grace), so exkes-api admits a new Execution only
-- while RUNNING and this timestamp is recent. Stored in the DB so a controller restart cannot
-- reopen admission; a controller that stops observing lets it go stale.

ALTER TABLE runtimes ADD COLUMN last_ready_observed_at TIMESTAMP NULL;
