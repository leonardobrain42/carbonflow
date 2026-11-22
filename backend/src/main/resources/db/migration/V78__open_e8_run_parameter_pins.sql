-- CF-OPEN-008 / #97. A rehearsal remembers which parameter pins it used.
-- A later pin leaves the stored run stale. The pins are not E8 admission.
ALTER TABLE app_open_e8_run
  ADD COLUMN fuel_pin_id uuid,
  ADD COLUMN ir_pin_id uuid,
  ADD COLUMN dy_pin_id uuid,
  ADD CONSTRAINT app_open_e8_run_fuel_pin
    FOREIGN KEY (project_id, fuel_pin_id)
    REFERENCES app_open_project_parameter_binding(project_id, id),
  ADD CONSTRAINT app_open_e8_run_ir_pin
    FOREIGN KEY (project_id, ir_pin_id)
    REFERENCES app_open_project_parameter_binding(project_id, id),
  ADD CONSTRAINT app_open_e8_run_dy_pin
    FOREIGN KEY (project_id, dy_pin_id)
    REFERENCES app_open_project_parameter_binding(project_id, id);

ALTER TABLE app_open_e8_run ADD CONSTRAINT app_open_e8_run_reviewed_pins CHECK (
  state <> 'REVIEWED_INTERNAL'
  OR (fuel_pin_id IS NOT NULL AND ir_pin_id IS NOT NULL AND dy_pin_id IS NOT NULL)
);
