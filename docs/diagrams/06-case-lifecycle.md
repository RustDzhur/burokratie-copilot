# Жизненный цикл дела

Статусы канонические (`cases_status_chk`, `V4__cases_and_contacts.sql`). Допустимые переходы заданы в `cases/domain/CaseStatusMachine` и проверены `CaseLifecycleTest`; диаграмма показывает основные из них (полный граф — в коде).

```mermaid
stateDiagram-v2
  [*] --> NEW
  NEW --> IN_PROGRESS
  IN_PROGRESS --> WAITING_FOR_USER
  IN_PROGRESS --> WAITING_FOR_EXTERNAL_PARTY
  IN_PROGRESS --> WAITING_FOR_DOCUMENTS
  IN_PROGRESS --> WAITING_FOR_APPROVAL
  IN_PROGRESS --> ON_HOLD
  WAITING_FOR_USER --> IN_PROGRESS
  WAITING_FOR_EXTERNAL_PARTY --> IN_PROGRESS: ответ получен
  WAITING_FOR_DOCUMENTS --> IN_PROGRESS
  WAITING_FOR_APPROVAL --> IN_PROGRESS
  ON_HOLD --> IN_PROGRESS
  IN_PROGRESS --> COMPLETED: критерии завершения выполнены (план CA-05)
  COMPLETED --> CLOSED
  IN_PROGRESS --> CLOSED: осознанное закрытие с причиной
  CLOSED --> REOPENED
  COMPLETED --> REOPENED
  REOPENED --> IN_PROGRESS
```

Соответствие терминам ТЗ v2.0: OPEN = NEW; WAITING_FOR_EXTERNAL_RESPONSE = WAITING_FOR_EXTERNAL_PARTY; BLOCKED = ON_HOLD; CANCELLED = CLOSED + `close_reason=CANCELLED` (D-03).

```mermaid
flowchart LR
  classDef exists fill:#e8f1ff,stroke:#2f5fb3,color:#0b1f3a
  classDef plan fill:#fff6d6,stroke:#b38600,stroke-dasharray: 5 4,color:#4a3800
  G["цель + критерии (план CA-01)"]:::plan --> P["план = workflow_runs с case_id (CA-02)"]:::plan
  P --> S["шаги: workflow_run_steps, задачи tasks"]:::exists
  S --> A["approvals (уровень 3–4)"]:::exists
  S --> W["ожидание: wait_until, waiting_for_event"]:::exists
  A --> S
  W --> S
  S --> D["критерии выполнены → COMPLETED (план CA-05)"]:::plan
```
