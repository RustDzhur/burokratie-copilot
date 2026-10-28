'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { CardsSkeleton, PageHeader } from '@/components/app/resource-view';
import { Badge, Card, CardBody, EmptyState } from '@/components/ui';
import { ErrorState } from '@/components/ui/feedback';
import { buttonClass } from '@/components/ui/button-class';
import { Input, Select } from '@/components/ui/form';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { AgentConstellationSummary } from '@/lib/api/types';
import { constellationIndustryLabel, constellationText, constellationTimeSaved } from './catalog';
import styles from './constellations.module.css';

/** One preset card: what the chain solves, for which industries, and what it saves. */
function ConstellationCard({
  constellation,
}: {
  readonly constellation: AgentConstellationSummary;
}) {
  const t = useTranslations('constellations');
  const locale = useLocale();
  const name = constellationText(constellation.name, locale);

  return (
    <Card className={styles.card}>
      <CardBody className={styles.cardBody}>
        <div className={styles.cardTop}>
          <h2 className={styles.cardName}>
            <Link className={styles.cardNameLink} href={`/constellations/${constellation.id}`}>
              {name}
            </Link>
          </h2>
          <span className={styles.timePill}>
            {constellationTimeSaved(constellation.estimatedTimeSaved, locale, t)}
          </span>
        </div>

        <p className={styles.cardDescription}>
          {constellationText(constellation.description, locale)}
        </p>

        <div className={styles.industries}>
          {constellation.industries.map((industry) => (
            <Badge key={industry} tone="neutral" variant="outline">
              {constellationIndustryLabel(t, industry)}
            </Badge>
          ))}
        </div>

        <div className={styles.cardMeta}>
          <span>{t('card.steps', { count: constellation.steps })}</span>
          <span className={styles.implemented}>
            {t('card.implemented', { count: constellation.implementedSteps })}
          </span>
          {constellation.conceptSteps > 0 ? (
            <span className={styles.concept}>
              {t('card.concept', { count: constellation.conceptSteps })}
            </span>
          ) : null}
        </div>

        <div className={styles.cardFooter}>
          <Link
            href={`/constellations/${constellation.id}`}
            className={buttonClass({ variant: 'secondary', size: 'sm' })}
            aria-label={`${t('card.open')}: ${name}`}
          >
            {t('card.open')}
          </Link>
        </div>
      </CardBody>
    </Card>
  );
}

/** The constellation catalogue: 30 named chains over the agent catalogue. */
export default function ConstellationsPage() {
  const t = useTranslations('constellations');
  const tNav = useTranslations('nav');
  const locale = useLocale();
  const [query, setQuery] = useState('');
  const [industry, setIndustry] = useState('');
  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.constellations.list(signal),
    [],
  );

  const constellations = state.status === 'ready' ? state.data : [];

  const industries = useMemo(() => {
    const seen = new Set<string>();
    for (const constellation of constellations) {
      for (const value of constellation.industries) seen.add(value);
    }
    const collator = new Intl.Collator(locale);
    return [...seen].sort((a, b) =>
      collator.compare(constellationIndustryLabel(t, a), constellationIndustryLabel(t, b)),
    );
  }, [constellations, locale, t]);

  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return constellations.filter((constellation) => {
      if (industry !== '' && !constellation.industries.includes(industry)) return false;
      if (needle === '') return true;
      return [
        constellation.id,
        constellationText(constellation.name, 'de'),
        constellationText(constellation.name, 'ru'),
        constellationText(constellation.description, 'de'),
        constellationText(constellation.description, 'ru'),
        ...constellation.industries.map((value) => constellationIndustryLabel(t, value)),
      ]
        .join(' ')
        .toLowerCase()
        .includes(needle);
    });
  }, [constellations, industry, query, t]);

  if (state.status === 'loading') {
    return (
      <>
        <PageHeader title={tNav('items.constellations')} subtitle={t('subtitle')} />
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
        <PageHeader title={tNav('items.constellations')} subtitle={t('subtitle')} />
        <ErrorState error={state.error} title={title} onRetry={reload} />
      </>
    );
  }

  const steps = constellations.reduce((sum, item) => sum + item.steps, 0);
  const implemented = constellations.reduce((sum, item) => sum + item.implementedSteps, 0);

  return (
    <>
      <PageHeader
        title={tNav('items.constellations')}
        subtitle={t('subtitle')}
        meta={
          <p className={styles.stats}>
            {t('stats', { presets: constellations.length, steps, implemented })}
          </p>
        }
      />

      <div className={styles.toolbar}>
        <Input
          label={t('search.label')}
          placeholder={t('search.placeholder')}
          leadingIcon="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <Select
          label={t('filter.industry')}
          value={industry}
          onChange={(event) => setIndustry(event.target.value)}
        >
          <option value="">{t('filter.all')}</option>
          {industries.map((value) => (
            <option key={value} value={value}>
              {constellationIndustryLabel(t, value)}
            </option>
          ))}
        </Select>
      </div>

      <div aria-busy={isRefreshing || undefined} data-testid="constellations-grid">
        {shown.length === 0 ? (
          <EmptyState
            icon="sparkles"
            title={constellations.length === 0 ? t('empty') : t('emptyFiltered')}
            hint={constellations.length === 0 ? t('emptyHint') : undefined}
          />
        ) : (
          <ul className={styles.grid}>
            {shown.map((constellation) => (
              <li key={constellation.id}>
                <ConstellationCard constellation={constellation} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
