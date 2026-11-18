-- Existing published examples retain their obligation. Future rule versions may declare optional steps.
ALTER TABLE app_norm_rule ADD COLUMN optional boolean NOT NULL DEFAULT false;
