/**
 * Members, notifications, audit log, integrations and devices.
 *
 * Several of these routes answer with plain maps built from SQL rows, so their keys are the column names
 * (`display_name`, `last_login_at`). That is part of the wire contract and is written down here.
 */

import type {
  AuditEntry,
  AuditResult,
  DeviceSummary,
  IntegrationStatus,
  IntegrationSummary,
  MemberRole,
  MemberSummary,
  NotificationCategory,
  NotificationPreferences,
  NotificationSummary,
} from '../types';

/* -------------------------------- members -------------------------------- */

export interface MemberWire {
  readonly user_id: string;
  readonly display_name?: string | null;
  readonly email?: string | null;
  readonly status: string;
  readonly is_owner?: boolean;
  readonly last_login_at?: string | null;
  readonly role_codes?: string | null;
}

export interface PendingInvitationWire {
  readonly id: string;
  readonly email: string;
}

const ROLE_FROM_WIRE: Readonly<Record<string, MemberRole>> = {
  OWNER: 'owner',
  ADMIN: 'admin',
  MANAGER: 'admin',
  EMPLOYEE: 'member',
  READ_ONLY: 'member',
  ACCOUNTANT: 'accountant',
  AUDITOR: 'auditor',
};

const ROLE_TO_WIRE: Readonly<Record<MemberRole, string>> = {
  owner: 'OWNER',
  admin: 'ADMIN',
  member: 'EMPLOYEE',
  accountant: 'ACCOUNTANT',
  auditor: 'AUDITOR',
};

export const roleToWire = (role: MemberRole): string => ROLE_TO_WIRE[role];

/** The most privileged role a member holds is the one shown. */
const ROLE_RANK: readonly MemberRole[] = ['owner', 'admin', 'accountant', 'auditor', 'member'];

export function memberFromWire(wire: MemberWire): MemberSummary {
  const roles = (wire.role_codes ?? '')
    .split(',')
    .map((code) => ROLE_FROM_WIRE[code.trim()])
    .filter((role): role is MemberRole => role !== undefined);
  const role = ROLE_RANK.find((candidate) => roles.includes(candidate)) ?? 'member';
  return {
    id: wire.user_id,
    name: wire.display_name ?? wire.email ?? '',
    email: wire.email ?? '',
    role: wire.is_owner ? 'owner' : role,
    status: wire.status === 'ACTIVE' ? 'active' : 'disabled',
    lastActiveAt: wire.last_login_at ?? null,
    documentCount: null,
  };
}

export function invitedMemberFromWire(wire: PendingInvitationWire): MemberSummary {
  return {
    id: wire.id,
    name: wire.email,
    email: wire.email,
    role: 'member',
    status: 'invited',
    lastActiveAt: null,
    documentCount: null,
  };
}

/* ----------------------------- notifications ----------------------------- */

export interface NotificationWire {
  readonly id: string;
  readonly category: string;
  readonly titleKey: string;
  readonly bodyKey?: string | null;
  readonly parameters?: Readonly<Record<string, unknown>> | null;
  readonly actionUrl?: string | null;
  readonly read: boolean;
  readonly createdAt: string;
  readonly deliveries?: readonly { readonly channel: string }[];
}

export interface PreferenceWire {
  readonly category: string;
  readonly channel: string;
  readonly enabled: boolean;
  readonly frequency: string;
}

const NOTIFICATION_CATEGORY_FROM_WIRE: Readonly<Record<string, NotificationCategory>> = {
  TASK: 'task',
  DEADLINE: 'deadline',
  MISSED_DEADLINE: 'deadline',
  CONTRACT_CHANGE: 'deadline',
  NEW_MAIL: 'mail',
  EXTERNAL_RESPONSE: 'mail',
  DOCUMENT_REVIEW: 'review',
  APPROVAL: 'approval',
  INTEGRATION_ERROR: 'integration',
  SECURITY: 'integration',
  SYSTEM: 'integration',
  AUTOMATION_RESULT: 'automation',
  ARCHIVING: 'archive',
};

const CHANNEL_FROM_WIRE: Readonly<Record<string, 'in_app' | 'email' | 'push'>> = {
  IN_APP: 'in_app',
  EMAIL: 'email',
  PUSH: 'push',
};

/** `task.assigned` → `Task assigned`: the key stands in until the notification has a localized text. */
export function humanizeKey(key: string): string {
  const last = key.split('.').slice(-1)[0] ?? key;
  const words = last.replace(/[_-]+/g, ' ').trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function textParameter(wire: NotificationWire, name: string): string | null {
  const value = wire.parameters?.[name];
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

export function notificationFromWire(wire: NotificationWire): NotificationSummary {
  return {
    id: wire.id,
    // The platform writes the title and body of what it reports (a document that needs a decision, a task
    // that is overdue); the key is only a stand-in for notifications that carry no text of their own.
    title: textParameter(wire, 'title') ?? humanizeKey(wire.titleKey),
    body: textParameter(wire, 'body') ?? (wire.bodyKey ? humanizeKey(wire.bodyKey) : null),
    category: NOTIFICATION_CATEGORY_FROM_WIRE[wire.category] ?? 'task',
    channels: [
      ...new Set(
        (wire.deliveries ?? []).flatMap((delivery) => {
          const channel = CHANNEL_FROM_WIRE[delivery.channel];
          return channel ? [channel] : [];
        }),
      ),
    ],
    read: wire.read,
    receivedAt: wire.createdAt,
    link: wire.actionUrl ?? null,
    kind: wire.titleKey,
    facts: wire.parameters ?? undefined,
  };
}

const FREQUENCY_FROM_WIRE: Readonly<Record<string, 'immediate' | 'digest' | 'off'>> = {
  IMMEDIATE: 'immediate',
  DAILY_DIGEST: 'digest',
  WEEKLY_DIGEST: 'digest',
  NEVER: 'off',
};

const FREQUENCY_TO_WIRE = { immediate: 'IMMEDIATE', digest: 'DAILY_DIGEST', off: 'NEVER' } as const;

const SCREEN_CATEGORIES: readonly NotificationCategory[] = [
  'task',
  'deadline',
  'mail',
  'review',
  'approval',
  'integration',
  'automation',
  'archive',
];

/**
 * The backend keeps a preference per category and channel; the screen shows a frequency per category and an
 * on/off per channel. A screen category is "off" when none of its backend categories is enabled anywhere.
 */
export function preferencesFromWire(rows: readonly PreferenceWire[]): NotificationPreferences {
  const categories = Object.fromEntries(
    SCREEN_CATEGORIES.map((category) => {
      const own = rows.filter((row) => NOTIFICATION_CATEGORY_FROM_WIRE[row.category] === category);
      const active = own.find((row) => row.enabled && row.frequency !== 'NEVER');
      return [category, active ? (FREQUENCY_FROM_WIRE[active.frequency] ?? 'immediate') : own.length > 0 ? 'off' : 'immediate'];
    }),
  ) as NotificationPreferences['categories'];
  const channelOn = (channel: 'in_app' | 'email' | 'push'): boolean => {
    const own = rows.filter((row) => CHANNEL_FROM_WIRE[row.channel] === channel);
    return own.length === 0 ? channel === 'in_app' : own.some((row) => row.enabled);
  };
  return {
    categories,
    channels: { in_app: channelOn('in_app'), email: channelOn('email'), push: channelOn('push') },
  };
}

const WIRE_CATEGORIES = Object.keys(NOTIFICATION_CATEGORY_FROM_WIRE);
const WIRE_CHANNELS = { in_app: 'IN_APP', email: 'EMAIL', push: 'PUSH' } as const;

/** Expands the screen's preferences into the full category × channel matrix the backend stores. */
export function preferencesToWire(preferences: NotificationPreferences): PreferenceWire[] {
  return WIRE_CATEGORIES.flatMap((category) => {
    const screenCategory = NOTIFICATION_CATEGORY_FROM_WIRE[category];
    const frequency = screenCategory ? preferences.categories[screenCategory] ?? 'immediate' : 'immediate';
    return (Object.keys(WIRE_CHANNELS) as (keyof typeof WIRE_CHANNELS)[]).map((channel) => ({
      category,
      channel: WIRE_CHANNELS[channel],
      enabled: frequency !== 'off' && preferences.channels[channel],
      frequency: FREQUENCY_TO_WIRE[frequency],
    }));
  });
}

/* --------------------------------- audit --------------------------------- */

export interface AuditEventWire {
  readonly eventId: string;
  readonly actorLabel?: string | null;
  readonly actorType?: string | null;
  readonly action: string;
  readonly entityType?: string | null;
  readonly entityId?: string | null;
  readonly entityLabel?: string | null;
  readonly result: string;
  readonly correlationId?: string | null;
  readonly createdAt: string;
}

const AUDIT_RESULT_FROM_WIRE: Readonly<Record<string, AuditResult>> = {
  SUCCESS: 'success',
  DENIED: 'denied',
  FAILURE: 'failure',
};

export function auditEntryFromWire(wire: AuditEventWire): AuditEntry {
  return {
    id: wire.eventId,
    timestamp: wire.createdAt,
    actor: wire.actorLabel ?? wire.actorType ?? '',
    action: wire.action,
    objectType: wire.entityType ?? '',
    objectLabel: wire.entityLabel ?? wire.entityId ?? '',
    result: AUDIT_RESULT_FROM_WIRE[wire.result] ?? 'failure',
    correlationId: wire.correlationId ?? null,
    ipAddress: null,
  };
}

/* ------------------------------ integrations ------------------------------ */

export interface IntegrationWire {
  readonly id: string;
  readonly provider: string;
  readonly category: string;
  readonly display_name?: string | null;
  readonly status: string;
  readonly requires_owner_action?: boolean | null;
  readonly owner_action_hint?: string | null;
  readonly last_sync_at?: string | null;
}

const INTEGRATION_STATUS_FROM_WIRE: Readonly<Record<string, IntegrationStatus>> = {
  CONNECTED: 'connected',
  DEGRADED: 'error',
  ERROR: 'error',
  PENDING_AUTHORIZATION: 'pending',
  NOT_CONFIGURED: 'disconnected',
  DISABLED: 'disconnected',
  NOT_SUPPORTED: 'disconnected',
};

const INTEGRATION_CATEGORY_FROM_WIRE: Readonly<Record<string, IntegrationSummary['category']>> = {
  BANKING: 'bank',
  EMAIL: 'mail',
  STORAGE: 'storage',
  CALENDAR: 'calendar',
  DEVICE: 'device',
};

export function integrationFromWire(wire: IntegrationWire): IntegrationSummary {
  return {
    id: wire.id,
    name: wire.display_name ?? wire.provider,
    category: INTEGRATION_CATEGORY_FROM_WIRE[wire.category] ?? 'identity',
    status: INTEGRATION_STATUS_FROM_WIRE[wire.status] ?? 'disconnected',
    lastSyncAt: wire.last_sync_at ?? null,
    configurationRequired: wire.requires_owner_action ?? wire.status === 'NOT_CONFIGURED',
    description: wire.owner_action_hint ?? null,
  };
}

/* --------------------------------- devices --------------------------------- */

export interface DeviceWire {
  readonly id: string;
  readonly name?: string | null;
  readonly identifier: string;
  readonly device_type: string;
  readonly status: string;
  readonly last_seen_at?: string | null;
}

const ONLINE_WINDOW_MS = 10 * 60 * 1000;

export function deviceFromWire(wire: DeviceWire, now: Date = new Date()): DeviceSummary {
  const seen = wire.last_seen_at ? new Date(wire.last_seen_at).getTime() : Number.NaN;
  return {
    id: wire.id,
    name: wire.name ?? wire.identifier,
    kind:
      wire.device_type === 'MULTIFUNCTION'
        ? 'multifunction'
        : wire.device_type === 'SCANNER'
          ? 'scanner'
          : 'bridge',
    online: wire.status === 'ACTIVE' && Number.isFinite(seen) && now.getTime() - seen < ONLINE_WINDOW_MS,
    lastSeenAt: wire.last_seen_at ?? null,
  };
}
