'use client';

import Link from 'next/link';
import { useMemo, useState, type FormEvent } from 'react';
import { PageHeader } from '@/components/app/resource-view';
import { Badge, DataTable, EmptyState } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Checkbox, Input } from '@/components/ui/form';
import { ConfirmDialog, Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import type { Column } from '@/components/ui/data';
import { useFormatters, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type { ApiToken, ApiTokenCreated } from '@/lib/api/types';
import styles from './api-tokens.module.css';

/**
 * Fallback for an installation that answers the list without the scope catalogue. The server always
 * sends its own list and is the only authority; this keeps the form usable instead of empty.
 */
const FALLBACK_SCOPES: readonly string[] = [
  'documents:write',
  'documents:read',
  'agents.view',
  'agents.run',
];

/** Clipboard with a fallback for browsers that do not expose the async clipboard API. */
async function writeClipboard(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.append(area);
  area.select();
  const copied = document.execCommand('copy');
  area.remove();
  if (!copied) throw new Error('copy refused');
}

/**
 * API tokens: the credentials external systems use to deliver documents or run agents.
 *
 * <p>A token is a password with a name. The screen is built around two facts that follow from that:
 * the value exists in exactly one dialog, and it can be revoked at once. A list that could show the
 * value again would make every screenshot and support call a credential leak, so it shows the prefix
 * only — enough to recognise a token, not enough to use it.</p>
 */
export default function ApiTokensPage() {
  const t = useTranslations('apiTokens');
  const tErrors = useTranslations('errors');
  const formatters = useFormatters();
  const toast = useToast();
  const session = useSession();

  // The server decides; the screen only avoids showing what would be refused anyway. An unknown
  // permission list (an older session, a test harness) hides nothing.
  const permissions = session?.permissions;
  const canManage = permissions === undefined || permissions.includes('api_tokens.manage');

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.apiTokens.list(signal),
    [],
    { skip: !canManage, keepPreviousData: true },
  );

  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [selectedScopes, setSelectedScopes] = useState<readonly string[]>([]);
  const [scopeError, setScopeError] = useState<string | undefined>(undefined);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<ApiTokenCreated | null>(null);
  const [copied, setCopied] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ApiToken | null>(null);
  const [revoking, setRevoking] = useState(false);

  const tokens = state.status === 'ready' ? state.data.items : [];
  const availableScopes = useMemo(() => {
    const sent = state.status === 'ready' ? state.data.availableScopes : [];
    return sent.length > 0 ? sent : FALLBACK_SCOPES;
  }, [state]);

  const scopeLabel = (scope: string): string => {
    switch (scope) {
      case 'documents:write':
        return t('scope.documentsWrite');
      case 'documents:read':
        return t('scope.documentsRead');
      case 'agents.view':
        return t('scope.agentsView');
      case 'agents.run':
        return t('scope.agentsRun');
      default:
        // An unknown scope is shown as the server names it rather than hidden: hiding it would make
        // the token look less powerful than it is.
        return scope;
    }
  };

  const scopeHint = (scope: string): string | undefined => {
    switch (scope) {
      case 'documents:write':
        return t('scope.documentsWriteHint');
      case 'documents:read':
        return t('scope.documentsReadHint');
      case 'agents.view':
        return t('scope.agentsViewHint');
      case 'agents.run':
        return t('scope.agentsRunHint');
      default:
        return undefined;
    }
  };

  const openCreate = (): void => {
    setName('');
    setNameError(undefined);
    setSelectedScopes([]);
    setScopeError(undefined);
    setCreateOpen(true);
  };

  const closeCreate = (): void => {
    if (creating) return;
    setCreateOpen(false);
  };

  const toggleScope = (scope: string, checked: boolean): void => {
    setScopeError(undefined);
    setSelectedScopes((current) =>
      checked ? [...current, scope] : current.filter((entry) => entry !== scope),
    );
  };

  const createToken = async (): Promise<void> => {
    const trimmed = name.trim();
    if (trimmed.length < 2) {
      setNameError(t('create.nameRequired'));
      return;
    }
    if (selectedScopes.length === 0) {
      setScopeError(t('create.scopesRequired'));
      return;
    }
    setCreating(true);
    try {
      const token = await api.apiTokens.create({ name: trimmed, scopes: selectedScopes });
      setCreated(token);
      setCopied(false);
      setCreateOpen(false);
      setName('');
      setSelectedScopes([]);
      reload();
    } catch (error) {
      const apiError = toApiError(error);
      // 409 is the per-workspace limit; it has a sentence of its own instead of the generic error.
      toast.error(apiError.kind === 'conflict' ? t('create.limitReached') : t('create.failed'));
    } finally {
      setCreating(false);
    }
  };

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    void createToken();
  };

  const closeCreated = (): void => {
    setCreated(null);
    setCopied(false);
  };

  const copyToken = async (): Promise<void> => {
    if (!created) return;
    try {
      await writeClipboard(created.token);
      setCopied(true);
      toast.success(t('created.copiedToast'));
    } catch {
      toast.error(t('created.copyFailed'));
    }
  };

  const confirmRevoke = async (): Promise<void> => {
    if (!revokeTarget) return;
    setRevoking(true);
    try {
      await api.apiTokens.revoke(revokeTarget.id);
      toast.success(t('revoke.done'));
      setRevokeTarget(null);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setRevoking(false);
    }
  };

  const columns: readonly Column<ApiToken>[] = [
    {
      key: 'name',
      header: t('list.name'),
      render: (row) => <span className={styles.name}>{row.name}</span>,
    },
    {
      key: 'prefix',
      header: t('list.prefix'),
      render: (row) => <code className={styles.prefix}>{row.prefix}…</code>,
    },
    {
      key: 'scopes',
      header: t('list.scopes'),
      render: (row) => (
        <span className={styles.scopes}>
          {row.scopes.length === 0 ? (
            <span className={styles.muted}>{t('list.noScopes')}</span>
          ) : (
            row.scopes.map((scope) => (
              <Badge key={scope} tone="neutral" variant="outline" size="sm">
                {scopeLabel(scope)}
              </Badge>
            ))
          )}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: t('list.createdAt'),
      render: (row) =>
        row.createdAt ? (
          <time dateTime={row.createdAt}>{formatters.date(row.createdAt)}</time>
        ) : (
          <span className={styles.muted}>–</span>
        ),
    },
    {
      key: 'lastUsedAt',
      header: t('list.lastUsedAt'),
      render: (row) =>
        row.lastUsedAt ? (
          <time dateTime={row.lastUsedAt}>{formatters.dateTime(row.lastUsedAt)}</time>
        ) : (
          <span className={styles.muted}>{t('list.neverUsed')}</span>
        ),
    },
    {
      key: 'status',
      header: t('list.status'),
      render: (row) => (
        <span className={styles.status}>
          {row.active ? (
            <Badge tone="success">{t('list.active')}</Badge>
          ) : (
            <Badge tone="neutral">{t('list.revoked')}</Badge>
          )}
          {row.revokedAt ? (
            <span className={styles.muted}>
              {t('list.revokedAt', { date: formatters.date(row.revokedAt) })}
            </span>
          ) : null}
        </span>
      ),
    },
  ];

  if (!canManage) {
    return (
      <>
        <PageHeader title={t('title')} subtitle={t('subtitle')} />
        <InlineAlert tone="warning" title={t('forbidden.title')}>
          {t('forbidden.description')}
        </InlineAlert>
        <p>
          <Link href="/settings">{t('forbidden.back')}</Link>
        </p>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={t('title')}
        subtitle={t('subtitle')}
        actions={
          <Button variant="primary" iconLeft="plus" onClick={openCreate}>
            {t('create.action')}
          </Button>
        }
      />

      <InlineAlert tone="info" title={t('intro.title')}>
        {t('intro.text')}
      </InlineAlert>

      <p className={styles.summary} aria-live="polite">
        {t('list.count', { count: state.status === 'ready' ? state.data.count : tokens.length })}
      </p>

      {state.status === 'error' ? (
        <InlineAlert tone="warning" title={t('loadFailed.title')}>
          {tErrors(state.error.kind)}
        </InlineAlert>
      ) : null}

      <DataTable
        caption={t('list.caption')}
        columns={columns}
        rows={tokens}
        rowKey={(row) => row.id}
        loading={state.status === 'loading' || isRefreshing}
        emptyState={
          <EmptyState
            icon="key"
            title={t('list.empty.title')}
            description={t('list.empty.description')}
            actions={
              <Button variant="primary" iconLeft="plus" onClick={openCreate}>
                {t('create.action')}
              </Button>
            }
          />
        }
        rowActions={(row) =>
          row.active ? (
            <Button variant="ghost" size="sm" onClick={() => setRevokeTarget(row)}>
              {t('list.revoke')}
            </Button>
          ) : null
        }
      />

      <Dialog
        open={createOpen}
        onClose={closeCreate}
        title={t('create.title')}
        description={t('create.description')}
        footer={
          <>
            <Button variant="ghost" onClick={closeCreate} disabled={creating}>
              {t('create.cancel')}
            </Button>
            <Button variant="primary" loading={creating} onClick={() => void createToken()}>
              {t('create.submit')}
            </Button>
          </>
        }
      >
        <form className={styles.form} onSubmit={submit}>
          <Input
            label={t('create.name')}
            placeholder={t('create.namePlaceholder')}
            hint={t('create.nameHint')}
            value={name}
            maxLength={200}
            autoFocus
            required
            error={nameError}
            onChange={(event) => {
              setName(event.target.value);
              setNameError(undefined);
            }}
          />
          <fieldset className={styles.scopeChoice}>
            <legend className={styles.legend}>{t('create.scopes')}</legend>
            <p className={styles.legendHint}>{t('create.scopesHint')}</p>
            {availableScopes.map((scope) => (
              <Checkbox
                key={scope}
                label={scopeLabel(scope)}
                description={scopeHint(scope)}
                checked={selectedScopes.includes(scope)}
                onChange={(event) => toggleScope(scope, event.target.checked)}
              />
            ))}
            {scopeError ? (
              <p className={styles.error} role="alert">
                {scopeError}
              </p>
            ) : null}
          </fieldset>
        </form>
      </Dialog>

      <Dialog
        open={created !== null}
        // A click beside the dialog must not throw away a credential that cannot be shown again.
        closeOnBackdrop={false}
        onClose={closeCreated}
        title={t('created.title')}
        description={created ? t('created.description', { name: created.name }) : undefined}
        footer={
          <Button variant="primary" onClick={closeCreated}>
            {t('created.done')}
          </Button>
        }
      >
        {created ? (
          <div className={styles.created}>
            <InlineAlert tone="warning" title={t('created.onceTitle')}>
              {created.warning || t('created.once')}
            </InlineAlert>
            <code className={styles.token}>{created.token}</code>
            <div className={styles.createdActions}>
              <Button
                variant="secondary"
                iconLeft={copied ? 'check' : 'copy'}
                onClick={() => void copyToken()}
              >
                {copied ? t('created.copied') : t('created.copy')}
              </Button>
            </div>
            <dl className={styles.meta}>
              <div>
                <dt>{t('list.prefix')}</dt>
                <dd>
                  <code className={styles.prefix}>{created.prefix}…</code>
                </dd>
              </div>
              <div>
                <dt>{t('list.scopes')}</dt>
                <dd className={styles.scopes}>
                  {created.scopes.map((scope) => (
                    <Badge key={scope} tone="neutral" variant="outline" size="sm">
                      {scopeLabel(scope)}
                    </Badge>
                  ))}
                </dd>
              </div>
              <div>
                <dt>{t('created.rateLimit')}</dt>
                <dd>{t('created.rateLimitValue', { limit: created.rateLimitPerMinute })}</dd>
              </div>
            </dl>
          </div>
        ) : null}
      </Dialog>

      <ConfirmDialog
        open={revokeTarget !== null}
        title={t('revoke.title')}
        description={
          revokeTarget ? t('revoke.description', { name: revokeTarget.name }) : undefined
        }
        confirmLabel={t('revoke.confirm')}
        loading={revoking}
        onCancel={() => {
          if (!revoking) setRevokeTarget(null);
        }}
        onConfirm={() => void confirmRevoke()}
      />
    </>
  );
}
