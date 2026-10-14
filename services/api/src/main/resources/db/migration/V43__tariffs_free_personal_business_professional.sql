-- =====================================================================================
-- V43 - the price list: Free, Personal, Business, Professional
--
-- A plan is a tier (what the account may do and how much), a cabinet is the kind of account (a person, a family, a
-- company: what the interface shows). The two are independent: a family on Personal sees the family area and no
-- invoices, a company on Business sees invoices and contracts, a company on Professional also sees roles, the audit
-- trail and the advisor area.
--
--   tier                0 Free, 1 Personal, 2 Business, 3 Professional
--   audiences           the kinds of cabinet the plan is offered to
--   analyses_per_month  how many documents are analysed per calendar month; null means no limit
--   features            the keys the interface lists under the plan
-- =====================================================================================

ALTER TABLE plans ADD COLUMN audiences varchar(60) NOT NULL DEFAULT 'private,family,company';
ALTER TABLE plans ADD COLUMN tier integer NOT NULL DEFAULT 1;
ALTER TABLE plans ADD COLUMN analyses_per_month integer;
ALTER TABLE plans ADD COLUMN features text NOT NULL DEFAULT '';

UPDATE plans SET is_active = false WHERE code IN ('PRIVATE', 'FAMILY', 'COMPANY');
UPDATE plans SET tier = 1 WHERE code IN ('PRIVATE', 'FAMILY');
UPDATE plans SET tier = 2 WHERE code = 'COMPANY';

INSERT INTO plans (code, name, account_type, price_cents, sort_order, audiences, tier, analyses_per_month, features) VALUES
 ('FREE', 'Free', 'ANY', 0, 10, 'private,family,company', 0, 3,
  'ANALYSES_LIMITED,DOCUMENTS,DEADLINES_BASIC,HISTORY'),
 ('PERSONAL', 'Personal', 'PRIVATE_PERSON', 790, 20, 'private,family', 1, NULL,
  'ANALYSES_UNLIMITED,EXPLAIN,DEADLINES,CASES,REMINDERS,HISTORY,MAILBOX,CALENDAR_SYNC,TELEGRAM,FAMILY,CONTRACTS_BASIC'),
 ('BUSINESS', 'Business', 'COMPANY', 2900, 30, 'company', 2, NULL,
  'ANALYSES_UNLIMITED,EXPLAIN,DEADLINES,CASES,HISTORY,MAILBOX,CALENDAR_SYNC,TELEGRAM,WORKFLOWS,CONTRACTS,INVOICES,OBLIGATIONS,TEMPLATES,AUTOMATION'),
 ('PROFESSIONAL', 'Professional', 'COMPANY', 9900, 40, 'company', 3, NULL,
  'ANALYSES_UNLIMITED,EXPLAIN,DEADLINES,CASES,HISTORY,MAILBOX,CALENDAR_SYNC,TELEGRAM,WORKFLOWS,CONTRACTS,INVOICES,OBLIGATIONS,TEMPLATES,AUTOMATION,TEAM,ROLES,MULTI_ORG,AUDIT,INTEGRATIONS');

-- Customers already on the platform keep their trial and move to the plan that corresponds to their old one.
UPDATE subscriptions SET plan_code = 'PERSONAL' WHERE plan_code IN ('PRIVATE', 'FAMILY');
UPDATE subscriptions SET plan_code = 'BUSINESS' WHERE plan_code = 'COMPANY';
