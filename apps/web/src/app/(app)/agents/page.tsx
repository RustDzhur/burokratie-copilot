'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { CardsSkeleton, PageHeader } from '@/components/app/resource-view';
import { Badge, Card, CardBody, EmptyState } from '@/components/ui';
import { ErrorState } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/form';
import { buttonClass } from '@/components/ui/button-class';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { AgentCatalogEntry } from '@/lib/api/types';
import {
  AGENT_CATEGORIES,
  agentCategoryLabel,
  agentCategoryTone,
  agentTriggerLabel,
} from './catalog';
import styles from './agents.module.css';

/** One catalogue card: what the agent promises, what triggers it and how often it has run. */
function AgentCard({ agent }: { readonly agent: AgentCatalogEntry }) {
  const t = useTranslations('agents');
  return (
    <Card className={styles.card}>
      <CardBody className={styles.cardBody}>
        <div className={styles.cardTop}>
          <div>
            <h2 className={styles.cardName}>
              <Link className={styles.cardNameLink} href={`/agents/${agent.id}`}>
                {agent.name}
              </Link>
            </h2>
            <Badge tone={agentCategoryTone(agent.category)}>
              {agentCategoryLabel(t, agent.category)}
            </Badge>
          </div>
          {agent.implemented ? (
            <span className={styles.livePill} title={t('status.liveHint')}>
              {t('status.live')}
            </span>
          ) : (
            <span className={styles.konzeptPill} title={t('status.konzeptHint')}>
              {t('status.konzept')}
            </span>
          )}
        </div>

        <p className={styles.cardDescription}>{agent.description}</p>

        {agent.functions.length > 0 ? (
          <div>
            <p className={styles.cardSectionLabel}>{t('card.functions')}</p>
            <ul className={styles.cardList}>
              {agent.functions.map((fn: string) => (
                <li key={fn}>{fn}</li>
              ))}
            </ul>
          </div>
        ) : null}

        {agent.triggers.length > 0 ? (
          <div>
            <p className={styles.cardSectionLabel}>{t('card.triggers')}</p>
            <div className={styles.cardTriggers}>
              {agent.triggers.map((trigger: string) => (
                <Badge key={trigger} tone="neutral" variant="outline">
                  {agentTriggerLabel(t, trigger)}
                </Badge>
              ))}
            </div>
          </div>
        ) : null}

        <div className={styles.cardFooter}>
          <span className={styles.runCount} data-testid={`agent-runs-${agent.id}`}>
            {agent.runCount > 0 ? t('card.runs', { count: agent.runCount }) : t('card.neverRun')}
          </span>
          <Link
            href={`/agents/${agent.id}`}
            className={buttonClass({ variant: 'secondary', size: 'sm' })}
            aria-label={`${t('card.open')}: ${agent.name}`}
          >
            {t('card.open')}
          </Link>
        </div>
      </CardBody>
    </Card>
  );
}

/** The Agent catalogue: a searchable grid over everything the backend offers. */
export default function AgentsPage() {
  const t = useTranslations('agents');
  const tNav = useTranslations('nav');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const { state, reload, isRefreshing } = useApiResource((signal) => api.agents.list(signal), []);

  const agents = state.status === 'ready' ? state.data : [];
  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return agents.filter((agent: { id: string; name: string; category: string; description: string; functions: readonly string[]; triggers: readonly string[]; implemented: boolean; runCount: number }) => {
      if (category !== '' && agent.category !== category) return false;
      if (needle === '') return true;
      return (
        agent.name.toLowerCase().includes(needle) ||
        agent.description.toLowerCase().includes(needle)
      );
    });
  }, [agents, query, category]);

  if (state.status === 'loading') {
    return (
      <>
        <PageHeader title={tNav('items.agents')} subtitle={t('subtitle')} />
        <CardsSkeleton count={8} />
      </>
    );
  }

  if (state.status === 'error') {
    const kind = state.error.kind;
    const title =
      kind === 'forbidden'
        ? t('error.forbidden')
        : kind === 'notFound'
          ? t('error.notFound')
          : kind === 'unauthorized'
            ? t('error.unauthorized')
            : undefined;
    return (
      <>
        <PageHeader title={tNav('items.agents')} subtitle={t('subtitle')} />
        <ErrorState error={state.error} title={title} onRetry={reload} />
      </>
    );
  }

  return (
    <>
      <PageHeader title={tNav('items.agents')} subtitle={t('subtitle')} />

      <div className={styles.toolbar}>
        <Input
          label={t('search.label')}
          placeholder={t('search.placeholder')}
          leadingIcon="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <Select
          label={t('filter.category')}
          value={category}
          onChange={(event) => setCategory(event.target.value)}
        >
          <option value="">{t('filter.all')}</option>
          {AGENT_CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {t(`category.${value}` as 'category.documents')}
            </option>
          ))}
        </Select>
      </div>

      <div aria-busy={isRefreshing || undefined} data-testid="agents-grid">
        {shown.length === 0 ? (
          <EmptyState
            icon="zap"
            title={agents.length === 0 ? t('empty') : t('emptyFiltered')}
            hint={agents.length === 0 ? t('emptyHint') : undefined}
          />
        ) : (
          <ul className={styles.grid}>
            {shown.map((agent) => (
              <li key={agent.id}>
                <AgentCard agent={agent} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
