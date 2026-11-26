-- D19: every candidate decision records the BASIS it was taken on. OWNER_INTERNAL is the product owner's technical
-- decision (the owner may also have imported the candidate); INDEPENDENT is a review by someone other than the importer.
-- OWNER_INTERNAL never stands in for an independent review and does not admit anything. Additive: decisions taken before
-- this migration required importer <> reviewer, so they are INDEPENDENT.
ALTER TABLE app_fleet_alias_candidate_decision
 ADD COLUMN decision_basis text NOT NULL DEFAULT 'INDEPENDENT' CHECK (decision_basis IN ('INDEPENDENT','OWNER_INTERNAL'));
