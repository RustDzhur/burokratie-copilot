'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { Icon, type IconName } from '@/components/icons';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/data';
import { FileDropzone, UploadList, useUploadQueue } from '@/components/ui/file-dropzone';
import { InlineAlert, ProgressBar } from '@/components/ui/feedback';
import { Input } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import type { ContactImportReport, SetupState } from '@/lib/api/types';
import styles from './setup.module.css';

interface StepDefinition {
  readonly id: 'cabinet' | 'mandanten' | 'documents';
  readonly titleKey: 'step.cabinet.title' | 'step.mandanten.title' | 'step.documents.title';
  readonly descriptionKey:
    | 'step.cabinet.description'
    | 'step.mandanten.description'
    | 'step.documents.description';
  readonly icon: IconName;
}

/**
 * The first-run wizard of the Kanzlei edition (§6.1): three steps, five minutes.
 *
 * <ol>
 *   <li>"Wer sind Sie?" — the name of the office, the one mandatory text field.</li>
 *   <li>"Ihre Mandanten" — three clients by hand or from a CSV file. Suggested, never required.</li>
 *   <li>"Erste Dokumente" — drop the first documents in, or look at the demo cabinet.</li>
 * </ol>
 *
 * <p>After step 3 the person lands in the Posteingang with the documents already being processed. Nothing
 * else is asked: the wizard borrows the ordinary APIs ({@code contacts}, {@code contacts/import},
 * {@code documents}) instead of growing a second implementation of them.</p>
 */
const STEPS = [
  {
    id: 'cabinet',
    titleKey: 'step.cabinet.title',
    descriptionKey: 'step.cabinet.description',
    icon: 'user',
  },
  {
    id: 'mandanten',
    titleKey: 'step.mandanten.title',
    descriptionKey: 'step.mandanten.description',
    icon: 'users',
  },
  {
    id: 'documents',
    titleKey: 'step.documents.title',
    descriptionKey: 'step.documents.description',
    icon: 'inbox',
  },
] as const satisfies readonly StepDefinition[];

/** The clients already created, from the client list or from the form on the screen. */
interface ClientRow {
  readonly id: string;
  readonly name: string;
  readonly email: string | null;
  readonly city: string | null;
}

/** Reads the chosen CSV file; FileReader rather than File.text() so older browsers and jsdom agree. */
function readText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error('The file could not be read'));
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.readAsText(file);
  });
}

export default function SetupPage() {
  const t = useTranslations('setup');
  const tCommon = useTranslations('common');
  const tStates = useTranslations('states');
  const tErrors = useTranslations('errors');
  const router = useRouter();
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement | null>(null);

  const [state, setState] = useState<SetupState | null>(null);
  const [loading, setLoading] = useState(true);
  const [stepIndex, setStepIndex] = useState(0);
  const [cabinetName, setCabinetName] = useState('');
  const [clients, setClients] = useState<readonly ClientRow[]>([]);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [city, setCity] = useState('');
  const [report, setReport] = useState<ContactImportReport | null>(null);
  const [importing, setImporting] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const queue = useUploadQueue({
    upload: async (file, context) => {
      await api.documents.upload(file, {
        onProgress: context.onProgress,
        signal: context.signal,
        idempotencyKey: true,
      });
    },
    onUploaded: () => {
      toast.success(tStates('success.created'), t('step.documents.title'));
    },
    onFailed: (file, message) => {
      toast.error(tErrors('network'), `${file.name}: ${message}`);
    },
  });

  // The wizard resumes where the cabinet was left: the name and the clients already exist on the server.
  useEffect(() => {
    let active = true;
    let redirecting = false;
    void (async () => {
      try {
        const current = await api.setup.state();
        if (!active) return;
        // A cabinet that is already set up has nothing to do here; the Posteingang is the home of the product.
        // The wizard stays hidden while the router leaves, so a finished cabinet never sees step 1 flash.
        if (current.completed) {
          redirecting = true;
          router.replace('/documents');
          return;
        }
        setState(current);
        setCabinetName(current.cabinetName);
        try {
          const existing = await api.mandanten.list();
          if (active) {
            setClients(
              existing.map((row) => ({
                id: row.id,
                name: row.displayName,
                email: row.email,
                city: row.city,
              })),
            );
          }
        } catch {
          // Reading the list may be refused to a person who may still create clients; the wizard itself works.
        }
      } catch (caught) {
        if (active) setError(toApiError(caught));
      } finally {
        if (active && !redirecting) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [router]);

  const uploading = queue.items.some(
    (item) => item.status === 'uploading' || item.status === 'queued',
  );

  const next = async (): Promise<void> => {
    setError(null);
    if (stepIndex === 0) {
      const nameToSave = cabinetName.trim();
      if (nameToSave === '') return;
      setPending(true);
      try {
        if (state?.canEditCabinet) {
          const updated = await api.setup.renameCabinet(nameToSave);
          setState(updated);
          setCabinetName(updated.cabinetName);
        }
        setStepIndex(1);
      } catch (caught) {
        setError(toApiError(caught));
      } finally {
        setPending(false);
      }
      return;
    }
    setStepIndex((index) => Math.min(STEPS.length - 1, index + 1));
  };

  const createClient = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (name.trim() === '') return;
    setPending(true);
    setError(null);
    try {
      const created = await api.contacts.create({
        name: name.trim(),
        contactType: 'organisation',
        ...(email.trim() === '' ? {} : { email: email.trim() }),
        ...(city.trim() === '' ? {} : { city: city.trim() }),
      });
      setClients((current) => [
        ...current,
        { id: created.id, name: created.name, email: created.email ?? null, city: created.city ?? null },
      ]);
      setName('');
      setEmail('');
      setCity('');
      toast.success(tCommon('actions.save'), t('step.mandanten.created'));
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  const importFile = async (event: ChangeEvent<HTMLInputElement>): Promise<void> => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setImporting(true);
    setError(null);
    try {
      const content = await readText(file);
      const result = await api.contacts.importCsv(content);
      setReport(result);
      const refreshed = await api.mandanten.list();
      setClients(
        refreshed.map((row) => ({
          id: row.id,
          name: row.displayName,
          email: row.email,
          city: row.city,
        })),
      );
      toast.success(
        t('step.mandanten.importSummary', {
          created: result.created,
          skipped: result.skipped,
        }),
        file.name,
      );
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setImporting(false);
    }
  };

  const finish = async (openDemo: boolean): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      await api.setup.complete();
      if (openDemo) {
        // The demo is a cabinet of its own; the visitor is signed into it here and leaves it with one click.
        await api.demo.start('ADVISOR');
      }
      router.push('/documents');
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  const step = STEPS[stepIndex] ?? STEPS[0];
  const isLastStep = stepIndex === STEPS.length - 1;
  const canLeaveStepOne = cabinetName.trim() !== '';

  if (loading) {
    return (
      <div className={styles.page}>
        <p role="status">{tStates('loading')}</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>{t('title')}</h1>
          <p className={styles.subtitle}>{t('subtitle')}</p>
        </div>
        <p className={styles.progressText}>
          {t('progress', { current: stepIndex + 1, total: STEPS.length })}
        </p>
      </header>

      <ProgressBar
        value={stepIndex + 1}
        max={STEPS.length}
        label={t('progress', { current: stepIndex + 1, total: STEPS.length })}
        className={styles.progress}
      />

      <ol className={styles.stepList} aria-label={t('title')}>
        {STEPS.map((item, index) => (
          <li
            key={item.id}
            className={index === stepIndex ? styles.stepActive : styles.stepItem}
            aria-current={index === stepIndex ? 'step' : undefined}
          >
            <span className={styles.stepIcon} aria-hidden="true">
              <Icon name={index < stepIndex ? 'check' : item.icon} size={14} />
            </span>
            <span className={styles.visuallyHidden}>{t(item.titleKey)}</span>
            <span aria-hidden="true">{index + 1}</span>
          </li>
        ))}
      </ol>

      {error ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}

      <Card tone="glass" padding="lg">
        <CardHeader
          title={t(step.titleKey)}
          description={t(step.descriptionKey)}
          icon={step.icon}
          headingLevel={2}
        />
        <CardBody>
          {step.id === 'cabinet' ? (
            <Input
              label={t('step.cabinet.name')}
              placeholder={t('step.cabinet.placeholder')}
              value={cabinetName}
              onChange={(event) => setCabinetName(event.target.value)}
              maxLength={200}
              disabled={state?.canEditCabinet === false || pending}
              hint={
                state?.canEditCabinet === false ? t('step.cabinet.readOnly') : undefined
              }
              autoComplete="organization"
              required
            />
          ) : null}

          {step.id === 'mandanten' ? (
            <div className={styles.column}>
              <p className={styles.hint}>{t('step.mandanten.hint')}</p>

              <form className={styles.clientForm} onSubmit={(event) => void createClient(event)}>
                <Input
                  label={t('step.mandanten.name')}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  maxLength={300}
                  required
                />
                <div className={styles.clientFormMeta}>
                  <Input
                    label={t('step.mandanten.email')}
                    type="email"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    autoComplete="email"
                  />
                  <Input
                    label={t('step.mandanten.city')}
                    value={city}
                    onChange={(event) => setCity(event.target.value)}
                    autoComplete="address-level2"
                  />
                </div>
                <Button
                  type="submit"
                  variant="secondary"
                  iconLeft="plus"
                  loading={pending}
                  disabled={name.trim() === '' || importing}
                >
                  {t('step.mandanten.create')}
                </Button>
              </form>

              <section className={styles.clients} aria-labelledby="setup-clients-title">
                <h3 className={styles.sectionTitle} id="setup-clients-title">
                  {t('step.mandanten.listTitle')}
                  <span className={styles.counter}>{clients.length}</span>
                </h3>
                {clients.length === 0 ? (
                  <p className={styles.empty}>{t('step.mandanten.empty')}</p>
                ) : (
                  <ul className={styles.clientList}>
                    {clients.map((client) => (
                      <li key={client.id} className={styles.clientRow}>
                        <span className={styles.clientName}>{client.name}</span>
                        <span className={styles.clientMeta}>
                          {[client.email, client.city].filter(Boolean).join(' · ')}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section className={styles.import} aria-labelledby="setup-import-title">
                <h3 className={styles.sectionTitle} id="setup-import-title">
                  {t('step.mandanten.importTitle')}
                </h3>
                <p className={styles.hint}>{t('step.mandanten.importHint')}</p>
                <input
                  ref={fileInput}
                  type="file"
                  accept=".csv,text/csv"
                  className={styles.fileInput}
                  onChange={(event) => void importFile(event)}
                  aria-label={t('step.mandanten.chooseFile')}
                  disabled={importing || pending}
                />
                <Button
                  variant="secondary"
                  iconLeft="upload"
                  loading={importing}
                  onClick={() => fileInput.current?.click()}
                >
                  {t('step.mandanten.chooseFile')}
                </Button>
                {report ? (
                  <div className={styles.report} role="status">
                    <p>
                      {t('step.mandanten.importSummary', {
                        created: report.created,
                        skipped: report.skipped,
                      })}
                    </p>
                    {report.lines.some((line) => line.status === 'SKIPPED') ? (
                      <ul className={styles.reportList}>
                        {report.lines
                          .filter((line) => line.status === 'SKIPPED')
                          .map((line) => (
                            <li key={line.line}>
                              {t('step.mandanten.importLine', {
                                line: line.line,
                                reason: line.reason ?? '',
                              })}
                            </li>
                          ))}
                      </ul>
                    ) : null}
                  </div>
                ) : null}
              </section>
            </div>
          ) : null}

          {step.id === 'documents' ? (
            <div className={styles.column}>
              <FileDropzone
                onSelect={(files) => queue.enqueue(files)}
                label={t('step.documents.description')}
                hint={t('step.documents.uploadHint')}
                disabled={pending || uploading}
              />
              <UploadList
                items={queue.items}
                onCancel={queue.cancel}
                onRetry={queue.retry}
                onRemove={queue.remove}
                title={t('step.documents.title')}
              />
              <p className={styles.hint}>{t('step.documents.hint')}</p>
            </div>
          ) : null}
        </CardBody>
      </Card>

      <div className={styles.actions}>
        <Button
          variant="ghost"
          onClick={() => setStepIndex((index) => Math.max(0, index - 1))}
          disabled={stepIndex === 0 || pending || uploading}
          iconLeft="chevron-left"
        >
          {tCommon('actions.back')}
        </Button>
        <div className={styles.actionsRight}>
          {isLastStep ? (
            <>
              <Button
                variant="secondary"
                onClick={() => void finish(true)}
                disabled={pending || uploading}
              >
                {t('step.documents.demo')}
              </Button>
              <Button
                variant="primary"
                loading={pending}
                onClick={() => void finish(false)}
                disabled={uploading}
                iconLeft="inbox"
              >
                {t('step.documents.finish')}
              </Button>
            </>
          ) : (
            <Button
              variant="primary"
              onClick={() => void next()}
              disabled={pending || (stepIndex === 0 && !canLeaveStepOne)}
              loading={pending}
              iconRight="chevron-right"
            >
              {tCommon('actions.next')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
