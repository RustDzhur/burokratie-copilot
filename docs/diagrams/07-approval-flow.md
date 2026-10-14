# Подтверждение значимого действия

Существует: заявка, решение, идемпотентность по `action_token`, срок `expires_at`, аудит (`approvals`, `approval_events`). Планируется: привязка к содержимому (D-09, CA-04).

```mermaid
sequenceDiagram
  autonumber
  participant U as Пользователь
  participant UI as Approval Center (план CA-07)
  participant API as API
  participant AP as approvals
  participant EX as Исполнение (письмо / платёж)
  participant AU as audit_events

  API->>AP: создать заявку (subject, уровень, срок)
  API->>AP: записать content_hash и subject_version (план CA-04)
  U->>UI: открыть заявку: материал, получатель, основания, предупреждения
  U->>API: решение APPROVE (с Idempotency-Key)
  API->>AP: проверить пользователя, tenant, роль, полномочия, срок
  API->>AP: сверить текущий content_hash с записанным (план CA-04)
  alt хеш совпал
    API->>EX: исполнить один раз (action_token)
    EX-->>API: результат провайдера (передача ≠ доставка)
    API->>AU: записать решение и результат
  else материал изменился
    API->>AP: статус EXPIRED, создать новую заявку
    API->>AU: записать отказ
  end
```
