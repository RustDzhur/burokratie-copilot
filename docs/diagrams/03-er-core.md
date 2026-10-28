# ER-диаграмма: основные сущности

Показаны существующие сущности (из миграций V1–V45) и планируемые добавки. Таблицы и столбцы названы так, как в миграциях; планируемые помечены `PLAN`.

```mermaid
erDiagram
  TENANTS ||--o{ DOCUMENTS : owns
  TENANTS ||--o{ CONTACTS : owns
  TENANTS ||--o{ CASES : owns
  TENANTS ||--o{ PARTIES : owns
  DOCUMENTS ||--o{ DOCUMENT_VERSIONS : has
  DOCUMENTS ||--o{ DOCUMENT_PAGES : has
  DOCUMENTS ||--o{ DOCUMENT_EXTRACTIONS : "facts (provenance)"
  DOCUMENTS ||--o{ DOCUMENT_MATCHES : "suggested links"
  DOCUMENTS ||--o{ DOCUMENT_RELATIONS : "doc to doc"
  DOCUMENTS }o--|| DOCUMENT_TYPES : typed
  DOCUMENTS }o--o| PARTIES : addressee
  DOCUMENT_TYPES ||--o| DOCUMENT_TYPE_SCENARIOS : "what to do"
  DOCUMENT_MATCHES }o--o| CASES : target
  DOCUMENT_MATCHES }o--o| CONTACTS : target
  DOCUMENT_MATCHES }o--o| CONTRACTS : target
  CONTACTS ||--o{ CONTACT_ADDRESSES : has
  CONTACTS ||--o{ SUPPLIERS : "finance role"
  CONTACTS ||--o{ CUSTOMERS : "finance role"
  CONTACTS ||--o{ CONTRACTS : counterparty
  CONTRACTS ||--o{ CONTRACT_OBLIGATIONS : has
  CONTRACTS ||--o{ CONTRACT_VERSIONS : has
  CONTRACTS ||--o{ INVOICES : "invoices.contract_id"
  CASES ||--o{ CASE_DOCUMENTS : links
  CASES ||--o{ CASE_EVENTS : history
  CASES ||--o{ TASKS : "tasks.case_id"
  CASES ||--o{ WORKFLOW_RUNS : "plan (workflow_runs.case_id)"
  WORKFLOW_RUNS ||--o{ WORKFLOW_RUN_STEPS : steps
  TASKS ||--o{ TASK_DEADLINES : "dates with provenance"
  APPROVALS ||--o{ APPROVAL_EVENTS : history
  AI_JOBS ||--o{ AI_MODEL_RUNS : runs
  DOCUMENT_EXTRACTIONS }o--o| AI_MODEL_RUNS : "ai_model_run_id"

  CONTACT_IDENTIFIERS_PLAN }o--|| CONTACTS : "PLAN BM-01"
  FACT_REVISIONS_PLAN }o--|| DOCUMENT_EXTRACTIONS : "PLAN BM-03"
  RADAR_FINDINGS_PLAN ||--o{ RADAR_FINDING_EVIDENCE_PLAN : "PLAN RD-01"
  RADAR_FINDINGS_PLAN }o--o| CASES : "PLAN"
  RADAR_FINDINGS_PLAN }o--o| AI_MODEL_RUNS : "PLAN explanation"
  CASE_COMPLETION_CRITERIA_PLAN }o--|| CASES : "PLAN CA-01"
```

Правила: новые таблицы — только перечисленные с суффиксом `_PLAN`; универсальных связей «любой-с-любым» нет (D-02).
