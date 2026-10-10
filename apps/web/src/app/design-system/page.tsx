'use client';

import { notFound } from 'next/navigation';
import { useState } from 'react';
import { Icon, iconNames } from '@/components/icons';
import { PageHeader, PageSection } from '@/components/app/resource-view';
import {
  Avatar,
  Badge,
  Breadcrumbs,
  Card,
  CardBody,
  CardHeader,
  Checkbox,
  ConfirmDialog,
  DataTable,
  DatePicker,
  Dialog,
  Drawer,
  EmptyState,
  ErrorState,
  Field,
  FileDropzone,
  IconButton,
  InlineAlert,
  Input,
  Pagination,
  ProgressBar,
  RadioGroup,
  SearchInput,
  Select,
  Skeleton,
  SkeletonCards,
  SkeletonTable,
  SkeletonText,
  Spinner,
  StatusPill,
  Switch,
  Tabs,
  Textarea,
  Tooltip,
  UploadList,
  Button,
} from '@/components/ui';
import type { Column } from '@/components/ui';
import { ApiError } from '@/lib/api/errors';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import styles from './design-system.module.css';

export const dynamic = 'force-dynamic';

interface DemoRow {
  readonly id: string;
  readonly name: string;
  readonly status: string;
  readonly amount: number;
}

const DEMO_ROWS: readonly DemoRow[] = [
  { id: '1', name: 'Beispielvorgang A', status: 'processed', amount: 128.5 },
  { id: '2', name: 'Beispielvorgang B', status: 'needs_review', amount: 89 },
  { id: '3', name: 'Beispielvorgang C', status: 'failed', amount: 0 },
];

/**
 * Development-only design-system showcase.
 *
 * Every primitive is rendered in every state so reviews can compare them side
 * by side. The route is blocked in production builds and `/_design` redirects
 * here (see next.config.ts). The table data below is explicitly labelled
 * demo data and never leaves this page.
 */
export default function DesignSystemPage() {
  if (process.env.NODE_ENV === 'production') notFound();

  const t = useTranslations('design');
  const tCommon = useTranslations('common');
  const tStates = useTranslations('states');
  const toast = useToast();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [checked, setChecked] = useState(true);
  const [switched, setSwitched] = useState(false);
  const [radio, setRadio] = useState('a');
  const [date, setDate] = useState<string | null>('2026-01-15');
  const [page, setPage] = useState(2);

  const columns: readonly Column<DemoRow>[] = [
    {
      key: 'name',
      header: tCommon('labels.unknown'),
      sortable: true,
      render: (row) => row.name,
    },
    {
      key: 'status',
      header: tCommon('labels.status'),
      render: (row) => <StatusPill label={row.status} status={row.status} />,
    },
    {
      key: 'amount',
      header: tCommon('labels.none'),
      align: 'end',
      render: (row) => String(row.amount),
    },
  ];

  return (
    <>
      <PageHeader title={t('title')} subtitle={t('subtitle')} />

      <PageSection title={t('section.typography')}>
        <Card>
          <CardBody>
            {/* Visual samples only: real heading elements would break the page outline. */}
            <p className={styles.sample1}>Überschrift 1</p>
            <p className={styles.sample2}>Überschrift 2</p>
            <p className={styles.sample3}>Überschrift 3</p>
            <p className={styles.sample4}>Überschrift 4</p>
            <p>
              Fließtext in der Standardgröße. Der Schnellstart braucht eine
              <strong> gute Lesbarkeit</strong> und <em>klare Hierarchien</em>.
            </p>
            <p className={styles.muted}>Sekundärer Text mit geringerer Betonung.</p>
            <code className={styles.code}>const answer = 42;</code>
          </CardBody>
        </Card>
      </PageSection>

      <PageSection title={t('section.colors')}>
        <ul className={styles.swatches}>
          {[
            'bg',
            'surface',
            'surface-2',
            'accent',
            'success',
            'warning',
            'danger',
            'info',
            'violet',
          ].map((token) => (
            <li key={token}>
              <span
                className={styles.swatch}
                style={{ backgroundColor: `var(--color-${token})` }}
              />
              <span className={styles.swatchLabel}>--color-{token}</span>
            </li>
          ))}
        </ul>
      </PageSection>

      <PageSection title={t('section.buttons')}>
        <div className={styles.stack}>
          {(['primary', 'secondary', 'ghost', 'danger', 'neon'] as const).map((variant) => (
            <div className={styles.row} key={variant}>
              <Button variant={variant}>{t('state.default')}</Button>
              <Button variant={variant} className={styles.hover}>
                {t('state.hover')}
              </Button>
              <Button variant={variant} className={styles.focus}>
                {t('state.focus')}
              </Button>
              <Button variant={variant} className={styles.active}>
                {t('state.active')}
              </Button>
              <Button variant={variant} disabled>
                {t('state.disabled')}
              </Button>
              <Button variant={variant} loading>
                {t('state.loading')}
              </Button>
            </div>
          ))}
          <div className={styles.row}>
            <Button size="sm" iconLeft="plus">
              Klein
            </Button>
            <Button size="md" iconLeft="upload">
              Mittel
            </Button>
            <Button size="lg" iconRight="arrow-right">
              Groß
            </Button>
            <IconButton label={tCommon('actions.search')} icon="search" />
            <IconButton label={tCommon('actions.close')} icon="x" variant="secondary" />
            <IconButton label={t('state.loading')} icon="refresh" loading />
            <IconButton label={t('state.disabled')} icon="trash" variant="danger" disabled />
          </div>
        </div>
      </PageSection>

      <PageSection title={t('section.forms')}>
        <div className={styles.grid}>
          <Input label="Eingabefeld" placeholder="Platzhalter" />
          <Input label="Mit Hinweis" hint="Hinweis zur Eingabe" />
          <Input label="Mit Fehler" error="Dieses Feld ist ungültig." defaultValue="abc" />
          <Input label="Deaktiviert" disabled defaultValue="Nicht änderbar" />
          <SearchInput label="Suche" placeholder="Suchen" defaultValue="Rechnung" />
          <Select
            label="Auswahl"
            options={[
              { value: 'a', label: 'Option A' },
              { value: 'b', label: 'Option B' },
            ]}
          />
          <Textarea label="Mehrzeilige Eingabe" placeholder="Notizen" />
          <DatePicker label="Datum" value={date} onChange={setDate} />
          <Field label="Feld-Wrapper" hint="Mit beliebigem Inhalt" required>
            {(ids) => <input className={styles.nativeInput} {...ids} />}
          </Field>
        </div>
        <div className={styles.grid}>
          <Checkbox
            label="Kontrollkästchen"
            description="Mit Beschreibung"
            checked={checked}
            onChange={(event) => setChecked(event.target.checked)}
          />
          <Checkbox label="Deaktiviert" disabled />
          <Checkbox label="Unbestimmt" indeterminate />
          <Switch
            label="Schalter"
            description="Ein/Aus"
            checked={switched}
            onChange={setSwitched}
          />
          <Switch
            label="Deaktivierter Schalter"
            checked={false}
            onChange={() => undefined}
            disabled
          />
          <RadioGroup
            legend="Auswahlgruppe"
            name="demo"
            value={radio}
            onChange={setRadio}
            options={[
              { value: 'a', label: 'Variante A' },
              { value: 'b', label: 'Variante B', description: 'Mit Beschreibung' },
              { value: 'c', label: 'Deaktiviert', disabled: true },
            ]}
          />
        </div>
      </PageSection>

      <PageSection title={t('section.feedback')}>
        <div className={styles.stack}>
          <div className={styles.row}>
            <Spinner size="sm" />
            <Spinner size="md" />
            <Spinner size="lg" />
          </div>
          <SkeletonText lines={3} />
          <div className={styles.row}>
            <Skeleton width={120} height={20} />
            <Skeleton variant="circle" width={40} height={40} />
          </div>
          <ProgressBar value={35} label="Fortschritt" showValue />
          <ProgressBar value={70} tone="success" label="Erfolg" showValue />
          <ProgressBar indeterminate label="Unbestimmt" />
          <InlineAlert tone="info">Information</InlineAlert>
          <InlineAlert tone="success">Erfolg</InlineAlert>
          <InlineAlert tone="warning">Warnung</InlineAlert>
          <InlineAlert tone="danger">Fehler</InlineAlert>
          <div className={styles.row}>
            <Button onClick={() => toast.success('Erfolg', 'Gespeichert')}>Toast: Erfolg</Button>
            <Button onClick={() => toast.error('Fehler', 'Netzwerk nicht erreichbar')}>
              Toast: Fehler
            </Button>
          </div>
          <SkeletonTable rows={3} columns={3} />
          <SkeletonCards count={2} />
          <EmptyState
            title={tStates('empty.title')}
            description={tStates('empty.description')}
            actions={<Button variant="primary">{tStates('empty.action')}</Button>}
          />
          <ErrorState
            error={
              new ApiError({
                kind: 'network',
                code: 'NETWORK',
                correlationId: 'demo-correlation-id',
              })
            }
            onRetry={() => undefined}
          />
        </div>
      </PageSection>

      <PageSection title={t('section.overlays')}>
        <div className={styles.row}>
          <Button onClick={() => setDialogOpen(true)}>Dialog öffnen</Button>
          <Button onClick={() => setDrawerOpen(true)}>Drawer öffnen</Button>
          <Button onClick={() => setConfirmOpen(true)}>Bestätigung</Button>
          <Tooltip content="Zusatzinformation">
            <Button variant="secondary">Tooltip</Button>
          </Tooltip>
        </div>
      </PageSection>

      <PageSection title={t('section.data')}>
        <div className={styles.row}>
          {(['neutral', 'info', 'success', 'warning', 'danger', 'accent'] as const).map((tone) => (
            <Badge key={tone} tone={tone}>
              {tone}
            </Badge>
          ))}
          {(['neutral', 'info', 'success', 'warning', 'danger'] as const).map((tone) => (
            <StatusPill key={tone} tone={tone} label={tone} />
          ))}
          <Avatar name="Anna Beispiel" size="sm" />
          <Avatar name="Anna Beispiel" size="md" status="online" />
          <Avatar name="Anna Beispiel" size="lg" status="away" />
        </div>
        <Breadcrumbs
          items={[
            { label: 'Start', href: '/dashboard' },
            { label: 'Dokumente', href: '/documents' },
            { label: 'Aktuelles Dokument' },
          ]}
        />
        <DataTable
          caption="Demonstrationstabelle"
          columns={columns}
          rows={DEMO_ROWS}
          rowKey={(row) => row.id}
          emptyState={<EmptyState title={tStates('empty.title')} />}
        />
        <DataTable
          caption="Leere Demonstrationstabelle"
          columns={columns}
          rows={[]}
          rowKey={(row) => row.id}
          emptyState={
            <EmptyState title={tStates('empty.title')} description={tStates('empty.description')} />
          }
        />
        <Pagination page={page} totalPages={12} totalItems={240} onPageChange={setPage} />
      </PageSection>

      <PageSection title={t('section.navigation')}>
        <Tabs
          label="Beispiel-Registerkarten"
          tabs={[
            { id: 'one', label: 'Erste', content: <p>Inhalt der ersten Registerkarte.</p> },
            {
              id: 'two',
              label: 'Zweite',
              badge: '3',
              content: <p>Inhalt der zweiten Registerkarte.</p>,
            },
            { id: 'three', label: 'Deaktiviert', disabled: true, content: null },
          ]}
        />
      </PageSection>

      <PageSection title={t('section.tokens')}>
        <ul className={styles.iconGrid}>
          {iconNames.map((name) => (
            <li key={name}>
              <Icon name={name} size={20} title={name} />
              <span>{name}</span>
            </li>
          ))}
        </ul>
      </PageSection>

      <PageSection title={t('section.feedback')}>
        <FileDropzone onSelect={() => undefined} />
        <UploadList
          items={[
            { id: '1', name: 'rechnung.pdf', size: 240_000, progress: 40, status: 'uploading' },
            { id: '2', name: 'bescheid.pdf', size: 120_000, progress: 100, status: 'done' },
            {
              id: '3',
              name: 'scan.tiff',
              size: 900_000,
              progress: 0,
              status: 'error',
              error: 'Übertragung fehlgeschlagen',
            },
          ]}
          onCancel={() => undefined}
          onRetry={() => undefined}
          onRemove={() => undefined}
        />
      </PageSection>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title="Beispiel-Dialog"
        description="Mit Fokusfalle, Escape und aria-modal."
      >
        <Card>
          <CardHeader title="Inhalt" description="Beliebiger Inhalt" headingLevel={3} />
          <CardBody>
            <Input label="Feld im Dialog" />
          </CardBody>
        </Card>
      </Dialog>

      <Drawer open={drawerOpen} onClose={() => setDrawerOpen(false)} title="Beispiel-Drawer">
        <p>Seitenleiste mit derselben Tastaturbedienung wie der Dialog.</p>
      </Drawer>

      <ConfirmDialog
        open={confirmOpen}
        title="Aktion bestätigen"
        description="Diese Aktion ist nicht umkehrbar."
        confirmLabel={tCommon('actions.confirm')}
        onConfirm={() => setConfirmOpen(false)}
        onCancel={() => setConfirmOpen(false)}
      />
    </>
  );
}
