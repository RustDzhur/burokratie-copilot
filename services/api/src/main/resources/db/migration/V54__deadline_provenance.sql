-- =====================================================================================
-- V54 - where a deadline comes from, precisely (task RD-05, spec 6.5)
--
-- task_deadlines already says how a date was found (source), the text it was read from (source_snippet), the document and
-- whether a person confirmed it. Two things were missing for a date to be checkable: the page of the document and, for a date
-- counted from a rule, the version of that rule. A date without a confirmation stays "to be verified" in every response.
-- =====================================================================================

ALTER TABLE task_deadlines ADD COLUMN source_page integer;
ALTER TABLE task_deadlines ADD COLUMN rule_version integer;

-- Dates already counted from a rule get the version of that rule.
UPDATE task_deadlines d SET rule_version = r.version FROM deadline_rules r WHERE d.rule_id = r.id AND d.rule_version IS NULL;
