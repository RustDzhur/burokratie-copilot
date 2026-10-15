'use client';

import { useState } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, EmptyState } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/form';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { RuleSetting, RuleSettingVersion } from '@/lib/api/types';

function RuleRow({ rule, onChanged }: { readonly rule: RuleSetting; readonly onChanged: () => void }) {
  const t = useTranslations('rules');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const [draft, setDraft] = useState<string>(String(rule.value));
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState('');
  const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<readonly RuleSettingVersion[] | null>(null);

  const parsed = (): unknown => (rule.type === 'NUMBER' ? Number(draft) : rule.type === 'BOOLEAN' ? draft === 'true' : draft);

  const save = async (kind: 'save' | 'reset'): Promise<void> => {
    if (reason.trim().length === 0) {
      setProblem(t('reasonRequired'));
      return;
    }
    setProblem('');
    setBusy(true);
    try {
      if (kind === 'save') await api.ruleSettings.change(rule.key, parsed(), reason.trim());
      else await api.ruleSettings.reset(rule.key, reason.trim());
      toast.success(t('saved'));
      setReason('');
      setHistory(null);
      onChanged();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const showHistory = async (): Promise<void> => {
    try {
      setHistory(await api.ruleSettings.history(rule.key));
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    }
  };

  const description = rule.description[locale] ?? rule.description.de ?? rule.key;
  const shown = (value: unknown): string =>
    rule.type === 'BOOLEAN' ? (value === true ? t('on') : t('off')) : String(value);
  const id = `rule-${rule.key}`;

  return (
    <li data-testid="rule" style={{ listStyle: 'none', borderBottom: '1px solid var(--border-subtle, #ddd)', padding: '0.75rem 0' }}>
      <strong id={`${id}-label`}>{description}</strong> <code>{rule.key}</code>{' '}
      {rule.overridden ? <Badge tone="info">{t('overridden')}</Badge> : <Badge tone="neutral">{t('standard')}</Badge>}
      <p>
        <small>
          {t('default')}: {shown(rule.default)} · {t('version')}: {rule.version}
          {rule.type === 'NUMBER' && rule.min !== null ? ` · ${t('range', { min: rule.min, max: rule.max ?? '' })}` : ''}
        </small>
      </p>
      {rule.type === 'BOOLEAN' ? (
        <Select label={t('value')} value={draft} onChange={(event) => setDraft(event.target.value)}>
          <option value="true">{t('on')}</option>
          <option value="false">{t('off')}</option>
        </Select>
      ) : rule.allowed ? (
        <Select label={t('value')} value={draft} onChange={(event) => setDraft(event.target.value)}>
          {rule.allowed.map((item) => (
            <option key={item} value={item}>
              {item}
            </option>
          ))}
        </Select>
      ) : (
        <Input label={t('value')} type="number" step="any" value={draft} onChange={(event) => setDraft(event.target.value)} />
      )}
      <Input label={t('reason')} value={reason} error={problem || undefined} onChange={(event) => setReason(event.target.value)} />
      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
        <Button variant="primary" loading={busy} onClick={() => void save('save')}>
          {t('save')}
        </Button>
        {rule.overridden ? (
          <Button variant="secondary" loading={busy} onClick={() => void save('reset')}>
            {t('reset')}
          </Button>
        ) : null}
        <Button variant="ghost" onClick={() => void showHistory()}>
          {t('history')}
        </Button>
      </div>
      {history ? (
        history.length === 0 ? (
          <p>{t('noHistory')}</p>
        ) : (
          <ol>
            {history.map((entry) => (
              <li key={entry.version}>
                {t('version')} {entry.version} · {formatDateTime(entry.changedAt, locale)}: {entry.newValue}{' '}
                <small>
                  ({t('previous')}: {entry.previousValue ?? '–'}){entry.reason ? ` · ${entry.reason}` : ''}
                </small>
              </li>
            ))}
          </ol>
        )
      ) : null}
    </li>
  );
}

/** Rules and sensitivity: the thresholds of the Radar and of document recognition, each change a new version with a reason. */
export default function RulesPage() {
  const t = useTranslations('rules');
  const tNav = useTranslations('nav');
  const { state, reload, isRefreshing } = useApiResource((signal) => api.ruleSettings.list(signal), []);

  return (
    <>
      <PageHeader title={tNav('items.rules')} subtitle={t('subtitle')} />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={(rules) => rules.length === 0}
        emptyState={<EmptyState icon="shield" title={t('empty')} />}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(rules) => (
          <Card>
            <CardBody>
              <ul style={{ padding: 0 }}>
                {rules.map((rule) => (
                  <RuleRow key={`${rule.key}:${rule.version}`} rule={rule} onChanged={reload} />
                ))}
              </ul>
            </CardBody>
          </Card>
        )}
      </ResourceView>
    </>
  );
}
