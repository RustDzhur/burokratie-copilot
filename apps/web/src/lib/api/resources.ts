/**
 * Endpoint catalogue.
 *
 * Every call the UI makes is declared here, grouped by the API groups from
 * docs/task.md §20. Components never build paths themselves, so a change in the
 * backend contract is a single-file change on the client.
 *
 * Mutating calls that create records or trigger processing pass
 * `idempotencyKey: true`, which makes the client generate a UUID v4 for the
 * `Idempotency-Key` header (§20: "идемпотентность, если требуется").
 */

import type { ApiClient, ListParams, Page, UploadOptions } from './client';
import type {
  AccessibilityPreferences,
  ArchiveEntry,
  AssistantAnswer,
  AuditEntry,
  AutomationSummary,
  CalendarEvent,
  CaseSummary,
  ContactSummary,
  ContractSummary,
  DashboardSummary,
  DeviceSummary,
  DocumentDetail,
  DocumentHistoryEntry,
  DocumentSummary,
  IntegrationSummary,
  InvoiceSummary,
  MailMessageSummary,
  MemberRole,
  MemberSummary,
  NotificationPreferences,
  NotificationSummary,
  PaymentSummary,
  ReportResult,
  ReviewItem,
  SecuritySettings,
  SessionUser,
  TaskSummary,
  TemplateSummary,
} from './types';

export interface LoginPayload {
  readonly email: string;
  readonly password: string;
  readonly rememberMe?: boolean;
}

/** What `GET /auth/me` answers. Mirrors `AuthController.MeResponse`; the screens never see this shape. */
interface MeResponse {
  readonly userId: string;
  readonly tenantId: string | null;
  readonly email: string;
  readonly displayName: string | null;
  readonly workspaceName: string | null;
  readonly accountType: string | null;
  readonly roles: readonly string[];
}

const ROLE_FROM_BACKEND: Readonly<Record<string, MemberRole>> = {
  OWNER: 'owner',
  ADMIN: 'admin',
  MANAGER: 'admin',
  EMPLOYEE: 'member',
  READ_ONLY: 'member',
  MEMBER: 'member',
  ACCOUNTANT: 'accountant',
  AUDITOR: 'auditor',
};

const ACCOUNT_TYPE_TO_BACKEND = {
  private: 'PRIVATE_PERSON',
  family: 'FAMILY_HOUSEHOLD',
  company: 'COMPANY',
} as const;

function workspaceTypeFromBackend(accountType: string | null): SessionUser['workspaceType'] {
  if (accountType === 'PRIVATE_PERSON') return 'private';
  if (accountType === 'FAMILY_HOUSEHOLD') return 'family';
  return 'company';
}

/** The one place that turns the backend's principal description into the user the screens work with. */
export function sessionUserFromMe(me: MeResponse): SessionUser {
  return {
    id: me.userId,
    name: me.displayName ?? me.email,
    email: me.email,
    roles: me.roles.flatMap((code) => {
      const role = ROLE_FROM_BACKEND[code];
      return role ? [role] : [];
    }),
    workspaceId: me.tenantId ?? '',
    workspaceName: me.workspaceName ?? '',
    workspaceType: workspaceTypeFromBackend(me.accountType),
  };
}

export interface RegisterPayload {
  readonly fullName: string;
  readonly email: string;
  readonly password: string;
  readonly accountType: 'private' | 'family' | 'company';
}

export interface OnboardingPayload {
  readonly workspaceName: string;
  readonly workspaceType: 'private' | 'family' | 'company';
  readonly sources: readonly string[];
  readonly notifications: readonly string[];
}

export interface ConfirmFieldsPayload {
  readonly fields: readonly { readonly name: string; readonly value: string }[];
}

export interface CreateTaskPayload {
  readonly title: string;
  readonly dueDate?: string;
  readonly priority: TaskSummary['priority'];
  readonly assigneeId?: string;
  readonly caseId?: string;
}

export interface AssistantQueryPayload {
  readonly question: string;
  readonly mode: 'question' | 'summarize' | 'draft';
  readonly documentIds?: readonly string[];
}

export interface ApiResources {
  readonly auth: {
    login: (payload: LoginPayload) => Promise<SessionUser>;
    logout: () => Promise<void>;
    register: (payload: RegisterPayload) => Promise<{ userId: string; email: string }>;
    verifyEmail: (payload: { token: string }) => Promise<void>;
    resendVerification: (payload: { email: string }) => Promise<void>;
    requestPasswordReset: (payload: { email: string }) => Promise<void>;
    confirmPasswordReset: (payload: { token: string; password: string }) => Promise<void>;
    session: (signal?: AbortSignal) => Promise<SessionUser>;
    completeOnboarding: (payload: OnboardingPayload) => Promise<SessionUser>;
  };
  readonly dashboard: {
    summary: (signal?: AbortSignal) => Promise<DashboardSummary>;
  };
  readonly documents: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<DocumentSummary>>;
    export: (params?: ListParams) => Promise<Blob>;
    get: (id: string, signal?: AbortSignal) => Promise<DocumentDetail>;
    history: (id: string, signal?: AbortSignal) => Promise<Page<DocumentHistoryEntry>>;
    upload: (file: File, options?: UploadOptions) => Promise<DocumentSummary>;
    reprocess: (id: string) => Promise<void>;
    downloadOriginal: (id: string) => Promise<Blob>;
    confirmFields: (id: string, payload: ConfirmFieldsPayload) => Promise<void>;
    archive: (id: string) => Promise<void>;
  };
  readonly review: {
    queue: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ReviewItem>>;
    confirm: (documentId: string, payload: ConfirmFieldsPayload) => Promise<void>;
    reject: (documentId: string, reason?: string) => Promise<void>;
  };
  readonly cases: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<CaseSummary>>;
    get: (id: string, signal?: AbortSignal) => Promise<CaseSummary>;
    create: (payload: {
      readonly title: string;
      readonly caseType?: string;
    }) => Promise<CaseSummary>;
  };
  readonly tasks: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<TaskSummary>>;
    create: (payload: CreateTaskPayload) => Promise<TaskSummary>;
    setStatus: (id: string, status: TaskSummary['status']) => Promise<TaskSummary>;
  };
  readonly calendar: {
    events: (params?: ListParams, signal?: AbortSignal) => Promise<Page<CalendarEvent>>;
    create: (payload: {
      readonly title: string;
      readonly date: string;
      readonly type: CalendarEvent['type'];
    }) => Promise<CalendarEvent>;
  };
  readonly contracts: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ContractSummary>>;
    expiring: (signal?: AbortSignal) => Promise<Page<ContractSummary>>;
    create: (payload: {
      readonly partner: string;
      readonly contractType: string;
      readonly startDate?: string;
      readonly endDate?: string;
    }) => Promise<ContractSummary>;
  };
  readonly invoices: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<InvoiceSummary>>;
    approve: (id: string) => Promise<InvoiceSummary>;
    create: (payload: {
      readonly number: string;
      readonly counterparty: string;
      readonly amount: string;
      readonly dueDate?: string;
      readonly direction: InvoiceSummary['direction'];
    }) => Promise<InvoiceSummary>;
  };
  readonly payments: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<PaymentSummary>>;
    match: (id: string, invoiceId: string) => Promise<PaymentSummary>;
  };
  readonly contacts: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ContactSummary>>;
    create: (payload: {
      readonly name: string;
      readonly contactType: ContactSummary['contactType'];
      readonly email?: string;
    }) => Promise<ContactSummary>;
  };
  readonly mail: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<MailMessageSummary>>;
    connect: (payload: { readonly provider: string }) => Promise<void>;
  };
  readonly templates: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<TemplateSummary>>;
    create: (payload: {
      readonly name: string;
      readonly templateType: string;
      readonly body?: string;
    }) => Promise<TemplateSummary>;
    get: (id: string, signal?: AbortSignal) => Promise<TemplateSummary>;
    save: (
      id: string,
      payload: { readonly name: string; readonly body: string },
    ) => Promise<TemplateSummary>;
  };
  readonly assistant: {
    ask: (payload: AssistantQueryPayload, signal?: AbortSignal) => Promise<AssistantAnswer>;
  };
  readonly automations: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<AutomationSummary>>;
    setEnabled: (id: string, enabled: boolean) => Promise<AutomationSummary>;
    create: (payload: {
      readonly name: string;
      readonly trigger: string;
    }) => Promise<AutomationSummary>;
  };
  readonly integrations: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<IntegrationSummary>>;
    disconnect: (id: string) => Promise<void>;
  };
  readonly devices: {
    list: (signal?: AbortSignal) => Promise<Page<DeviceSummary>>;
    scan: (id: string, profile?: string) => Promise<void>;
  };
  readonly members: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<MemberSummary>>;
    invite: (payload: {
      readonly email: string;
      readonly role: MemberSummary['role'];
    }) => Promise<void>;
    setRole: (id: string, role: MemberSummary['role']) => Promise<MemberSummary>;
  };
  readonly reports: {
    get: (type: string, period: string, signal?: AbortSignal) => Promise<ReportResult>;
    export: (type: string, period: string) => Promise<Blob>;
  };
  readonly archive: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ArchiveEntry>>;
    export: (params?: ListParams) => Promise<Blob>;
  };
  readonly notifications: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<NotificationSummary>>;
    markAllRead: () => Promise<void>;
    preferences: (signal?: AbortSignal) => Promise<NotificationPreferences>;
    savePreferences: (preferences: NotificationPreferences) => Promise<NotificationPreferences>;
  };
  readonly audit: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<AuditEntry>>;
    export: (params?: ListParams) => Promise<Blob>;
  };
  readonly settings: {
    security: (signal?: AbortSignal) => Promise<SecuritySettings>;
    revokeSession: (id: string) => Promise<void>;
    changePassword: (payload: {
      readonly currentPassword: string;
      readonly newPassword: string;
    }) => Promise<void>;
    setMfa: (enabled: boolean) => Promise<void>;
    accessibility: (signal?: AbortSignal) => Promise<AccessibilityPreferences>;
    saveAccessibility: (preferences: AccessibilityPreferences) => Promise<AccessibilityPreferences>;
  };
}

const withSignal = (signal?: AbortSignal): { signal?: AbortSignal } => (signal ? { signal } : {});

export function createResources(client: ApiClient): ApiResources {
  return {
    auth: {
      login: async (payload) => {
        // A fresh sign-in must never be answered from the idempotency cache: the reply carries Set-Cookie.
        await client.post<unknown>('/auth/login', { email: payload.email, password: payload.password });
        return sessionUserFromMe(await client.get<MeResponse>('/auth/me'));
      },
      logout: () => client.post<void>('/auth/logout'),
      register: async (payload) => {
        const created = await client.post<{ userId: string }>(
          '/auth/register',
          {
            email: payload.email,
            password: payload.password,
            displayName: payload.fullName,
            accountType: ACCOUNT_TYPE_TO_BACKEND[payload.accountType],
          },
          { idempotencyKey: true },
        );
        return { userId: created.userId, email: payload.email };
      },
      verifyEmail: (payload) =>
        client.post<void>('/auth/verify-email', payload, { idempotencyKey: true }),
      resendVerification: (payload) =>
        client.post<void>('/auth/verify-email/resend', payload, { idempotencyKey: true }),
      requestPasswordReset: (payload) =>
        client.post<void>('/auth/password-reset', payload, { idempotencyKey: true }),
      confirmPasswordReset: (payload) =>
        client.post<void>('/auth/password-reset/confirm', payload, { idempotencyKey: true }),
      session: async (signal) =>
        sessionUserFromMe(await client.get<MeResponse>('/auth/me', withSignal(signal))),
      completeOnboarding: (payload) =>
        client.post<SessionUser>('/auth/onboarding', payload, { idempotencyKey: true }),
    },
    dashboard: {
      summary: (signal) => client.get<DashboardSummary>('/dashboard/summary', withSignal(signal)),
    },
    documents: {
      list: (params, signal) =>
        client.list<DocumentSummary>('/documents', params, withSignal(signal)),
      export: (params) =>
        client.request<Blob>('/documents/export', { query: params, parse: 'blob' }),
      get: (id, signal) => client.get<DocumentDetail>(`/documents/${id}`, withSignal(signal)),
      history: (id, signal) =>
        client.list<DocumentHistoryEntry>(`/documents/${id}/history`, {}, withSignal(signal)),
      upload: (file, options) => client.upload<DocumentSummary>('/documents', file, options),
      reprocess: (id) =>
        client.post<void>(`/documents/${id}/reprocess`, undefined, { idempotencyKey: true }),
      downloadOriginal: (id) => client.request<Blob>(`/documents/${id}/content`, { parse: 'blob' }),
      confirmFields: (id, payload) =>
        client.post<void>(`/documents/${id}/fields/confirm`, payload, { idempotencyKey: true }),
      archive: (id) =>
        client.post<void>(`/documents/${id}/archive`, undefined, { idempotencyKey: true }),
    },
    review: {
      queue: (params, signal) =>
        client.list<ReviewItem>('/document-processing/review', params, withSignal(signal)),
      confirm: (documentId, payload) =>
        client.post<void>(`/document-processing/review/${documentId}/confirm`, payload, {
          idempotencyKey: true,
        }),
      reject: (documentId, reason) =>
        client.post<void>(
          `/document-processing/review/${documentId}/reject`,
          { reason },
          { idempotencyKey: true },
        ),
    },
    cases: {
      list: (params, signal) => client.list<CaseSummary>('/cases', params, withSignal(signal)),
      get: (id, signal) => client.get<CaseSummary>(`/cases/${id}`, withSignal(signal)),
      create: (payload) => client.post<CaseSummary>('/cases', payload, { idempotencyKey: true }),
    },
    tasks: {
      list: (params, signal) => client.list<TaskSummary>('/tasks', params, withSignal(signal)),
      create: (payload) => client.post<TaskSummary>('/tasks', payload, { idempotencyKey: true }),
      setStatus: (id, status) => client.patch<TaskSummary>(`/tasks/${id}`, { status }),
    },
    calendar: {
      events: (params, signal) =>
        client.list<CalendarEvent>('/calendar/events', params, withSignal(signal)),
      create: (payload) =>
        client.post<CalendarEvent>('/calendar/events', payload, { idempotencyKey: true }),
    },
    contracts: {
      list: (params, signal) =>
        client.list<ContractSummary>('/contracts', params, withSignal(signal)),
      expiring: (signal) =>
        client.list<ContractSummary>('/contracts', { filter: 'expiring' }, withSignal(signal)),
      create: (payload) =>
        client.post<ContractSummary>('/contracts', payload, { idempotencyKey: true }),
    },
    invoices: {
      list: (params, signal) =>
        client.list<InvoiceSummary>('/invoices', params, withSignal(signal)),
      approve: (id) =>
        client.post<InvoiceSummary>(`/invoices/${id}/approve`, undefined, { idempotencyKey: true }),
      create: (payload) =>
        client.post<InvoiceSummary>('/invoices', payload, { idempotencyKey: true }),
    },
    payments: {
      list: (params, signal) =>
        client.list<PaymentSummary>('/payments', params, withSignal(signal)),
      match: (id, invoiceId) =>
        client.post<PaymentSummary>(
          `/payments/${id}/match`,
          { invoiceId },
          { idempotencyKey: true },
        ),
    },
    contacts: {
      list: (params, signal) =>
        client.list<ContactSummary>('/contacts', params, withSignal(signal)),
      create: (payload) =>
        client.post<ContactSummary>('/contacts', payload, { idempotencyKey: true }),
    },
    mail: {
      list: (params, signal) =>
        client.list<MailMessageSummary>('/mail', params, withSignal(signal)),
      connect: (payload) => client.post<void>('/mail/connect', payload, { idempotencyKey: true }),
    },
    templates: {
      list: (params, signal) =>
        client.list<TemplateSummary>('/templates', params, withSignal(signal)),
      create: (payload) =>
        client.post<TemplateSummary>('/templates', payload, { idempotencyKey: true }),
      get: (id, signal) => client.get<TemplateSummary>(`/templates/${id}`, withSignal(signal)),
      save: (id, payload) =>
        client.put<TemplateSummary>(`/templates/${id}`, payload, { idempotencyKey: true }),
    },
    assistant: {
      ask: (payload, signal) =>
        client.post<AssistantAnswer>('/ai/assistant/query', payload, withSignal(signal)),
    },
    automations: {
      list: (params, signal) =>
        client.list<AutomationSummary>('/workflows', params, withSignal(signal)),
      setEnabled: (id, enabled) => client.patch<AutomationSummary>(`/workflows/${id}`, { enabled }),
      create: (payload) =>
        client.post<AutomationSummary>('/workflows', payload, { idempotencyKey: true }),
    },
    integrations: {
      list: (params, signal) =>
        client.list<IntegrationSummary>('/integrations', params, withSignal(signal)),
      disconnect: (id) =>
        client.post<void>(`/integrations/${id}/disconnect`, undefined, { idempotencyKey: true }),
    },
    devices: {
      list: (signal) => client.list<DeviceSummary>('/devices', {}, withSignal(signal)),
      scan: (id, profile) =>
        client.post<void>(`/devices/${id}/scan`, { profile }, { idempotencyKey: true }),
    },
    members: {
      list: (params, signal) => client.list<MemberSummary>('/users', params, withSignal(signal)),
      invite: (payload) =>
        client.post<void>('/users/invitations', payload, { idempotencyKey: true }),
      setRole: (id, role) => client.patch<MemberSummary>(`/users/${id}/role`, { role }),
    },
    reports: {
      get: (type, period, signal) =>
        client.get<ReportResult>(`/reports/${type}`, { query: { period }, ...withSignal(signal) }),
      export: (type, period) =>
        client.request<Blob>(`/reports/${type}/export`, { query: { period }, parse: 'blob' }),
    },
    archive: {
      list: (params, signal) =>
        client.list<ArchiveEntry>('/documents/archive', params, withSignal(signal)),
      export: (params) =>
        client.request<Blob>('/documents/archive/export', { query: params, parse: 'blob' }),
    },
    notifications: {
      list: (params, signal) =>
        client.list<NotificationSummary>('/notifications', params, withSignal(signal)),
      markAllRead: () =>
        client.post<void>('/notifications/read-all', undefined, { idempotencyKey: true }),
      preferences: (signal) =>
        client.get<NotificationPreferences>('/notifications/preferences', withSignal(signal)),
      savePreferences: (preferences) =>
        client.put<NotificationPreferences>('/notifications/preferences', preferences, {
          idempotencyKey: true,
        }),
    },
    audit: {
      list: (params, signal) => client.list<AuditEntry>('/audit', params, withSignal(signal)),
      export: (params) => client.request<Blob>('/audit/export', { query: params, parse: 'blob' }),
    },
    settings: {
      security: (signal) => client.get<SecuritySettings>('/settings/security', withSignal(signal)),
      revokeSession: (id) =>
        client.post<void>(`/settings/security/sessions/${id}/revoke`, undefined, {
          idempotencyKey: true,
        }),
      changePassword: (payload) =>
        client.post<void>('/settings/security/password', payload, { idempotencyKey: true }),
      setMfa: (enabled) =>
        client.post<void>('/settings/security/mfa', { enabled }, { idempotencyKey: true }),
      accessibility: (signal) =>
        client.get<AccessibilityPreferences>('/settings/accessibility', withSignal(signal)),
      saveAccessibility: (preferences) =>
        client.put<AccessibilityPreferences>('/settings/accessibility', preferences, {
          idempotencyKey: true,
        }),
    },
  };
}
