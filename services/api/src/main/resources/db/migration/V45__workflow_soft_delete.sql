-- An automation a person deleted is hidden and switched off; its runs stay as history. Its code is freed so that a new
-- automation of the same name can be created.
ALTER TABLE workflow_definitions ADD COLUMN deleted_at timestamptz;
