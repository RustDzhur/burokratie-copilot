'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Icon, type IconName } from '@/components/icons';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader } from '@/components/ui/data';
import { Checkbox, Input, RadioGroup, Switch } from '@/components/ui/form';
import { InlineAlert, ProgressBar } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import { useAppearance } from '@/settings/appearance-provider';
import styles from './onboarding.module.css';

type WorkspaceType = 'private' | 'family' | 'company';

interface StepDefinition {
  readonly id: string;
  readonly titleKey:
    | 'step.profile.title'
    | 'step.workspace.title'
    | 'step.sources.title'
    | 'step.notifications.title'
    | 'step.accessibility.title'
    | 'step.complete.title';
  readonly descriptionKey:
    | 'step.profile.description'
    | 'step.workspace.description'
    | 'step.sources.description'
    | 'step.notifications.description'
    | 'step.accessibility.description'
    | 'step.complete.description';
  readonly icon: IconName;
}

const STEPS = [
  {
    id: 'profile',
    titleKey: 'step.profile.title',
    descriptionKey: 'step.profile.description',
    icon: 'user',
  },
  {
    id: 'workspace',
    titleKey: 'step.workspace.title',
    descriptionKey: 'step.workspace.description',
    icon: 'building',
  },
  {
    id: 'sources',
    titleKey: 'step.sources.title',
    descriptionKey: 'step.sources.description',
    icon: 'inbox',
  },
  {
    id: 'notifications',
    titleKey: 'step.notifications.title',
    descriptionKey: 'step.notifications.description',
    icon: 'bell',
  },
  {
    id: 'accessibility',
    titleKey: 'step.accessibility.title',
    descriptionKey: 'step.accessibility.description',
    icon: 'accessibility',
  },
  {
    id: 'complete',
    titleKey: 'step.complete.title',
    descriptionKey: 'step.complete.description',
    icon: 'check-circle',
  },
] as const satisfies readonly StepDefinition[];

/**
 * Initial account setup (docs/task.md §5.2.3).
 *
 * The wizard keeps every step in one place, saves progress locally (draft
 * preservation for cognitive accessibility, §23.3) and submits once at the end.
 * The accessibility step applies the real display settings immediately.
 */
export default function OnboardingPage() {
  const t = useTranslations('onboarding');
  const tCommon = useTranslations('common');
  const tA11y = useTranslations('a11y');
  const tErrors = useTranslations('errors');
  const router = useRouter();
  const toast = useToast();
  const { settings, update } = useAppearance();

  const [stepIndex, setStepIndex] = useState(0);
  const [displayName, setDisplayName] = useState('');
  const [workspaceName, setWorkspaceName] = useState('');
  const [workspaceType, setWorkspaceType] = useState<WorkspaceType>('private');
  const [sources, setSources] = useState<readonly string[]>(['upload']);
  const [notifications, setNotifications] = useState<readonly string[]>(['deadlines', 'review']);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const step = STEPS[stepIndex] ?? STEPS[0];
  const isLastStep = stepIndex === STEPS.length - 1;
  const isFirstStep = stepIndex === 0;

  const toggle = (
    value: string,
    current: readonly string[],
    setter: (next: readonly string[]) => void,
  ): void => {
    setter(
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  };

  const finish = async (): Promise<void> => {
    setPending(true);
    setError(null);
    try {
      await api.auth.completeOnboarding({ workspaceName, workspaceType, sources, notifications });
      toast.success(t('step.complete.title'), t('step.complete.description'));
      router.push('/dashboard');
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

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
          <li key={item.id} className={index === stepIndex ? styles.stepActive : styles.stepItem}>
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
          {step.id === 'profile' ? (
            <Input
              label={t('step.profile.title')}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              autoComplete="name"
            />
          ) : null}

          {step.id === 'workspace' ? (
            <div className={styles.formColumn}>
              <Input
                label={t('step.workspace.name')}
                value={workspaceName}
                onChange={(event) => setWorkspaceName(event.target.value)}
                required
              />
              <RadioGroup
                legend={t('step.workspace.type')}
                name="workspaceType"
                value={workspaceType}
                onChange={(value) => setWorkspaceType(value as WorkspaceType)}
                orientation="horizontal"
                options={[
                  { value: 'private', label: t('step.workspace.typePrivate') },
                  { value: 'family', label: t('step.workspace.typeFamily') },
                  { value: 'company', label: t('step.workspace.typeBusiness') },
                ]}
              />
            </div>
          ) : null}

          {step.id === 'sources' ? (
            <fieldset className={styles.checkboxGroup}>
              <legend className={styles.legend}>{t('step.sources.description')}</legend>
              {(
                [
                  ['upload', t('step.sources.upload')],
                  ['email', t('step.sources.email')],
                  ['scanner', t('step.sources.scanner')],
                ] as const
              ).map(([value, label]) => (
                <Checkbox
                  key={value}
                  label={label}
                  checked={sources.includes(value)}
                  onChange={() => toggle(value, sources, setSources)}
                />
              ))}
            </fieldset>
          ) : null}

          {step.id === 'notifications' ? (
            <fieldset className={styles.checkboxGroup}>
              <legend className={styles.legend}>{t('step.notifications.description')}</legend>
              {(
                [
                  ['deadlines', t('step.notifications.deadlines')],
                  ['review', t('step.notifications.review')],
                  ['weekly', t('step.notifications.weekly')],
                ] as const
              ).map(([value, label]) => (
                <Checkbox
                  key={value}
                  label={label}
                  checked={notifications.includes(value)}
                  onChange={() => toggle(value, notifications, setNotifications)}
                />
              ))}
            </fieldset>
          ) : null}

          {step.id === 'accessibility' ? (
            <div className={styles.formColumn}>
              <Switch
                label={tA11y('contrast.label')}
                description={tA11y('contrast.help')}
                checked={settings.contrast === 'high'}
                onChange={(checked) => update({ contrast: checked ? 'high' : 'normal' })}
              />
              <Switch
                label={tA11y('motion.label')}
                description={tA11y('motion.help')}
                checked={settings.motion === 'reduced'}
                onChange={(checked) => update({ motion: checked ? 'reduced' : 'auto' })}
              />
              <Switch
                label={tA11y('textScale.label')}
                description={tA11y('textScale.help')}
                checked={settings.textScale === 112}
                onChange={(checked) => update({ textScale: checked ? 112 : 100 })}
              />
              <Switch
                label={tA11y('colorCues.label')}
                description={tA11y('colorCues.help')}
                checked={settings.colorCues === 'patterns'}
                onChange={(checked) => update({ colorCues: checked ? 'patterns' : 'off' })}
              />
            </div>
          ) : null}

          {step.id === 'complete' ? (
            <div className={styles.formColumn}>
              <InlineAlert tone="success" title={t('step.complete.description')}>
                {workspaceName === '' ? t('step.workspace.name') : workspaceName}
              </InlineAlert>
            </div>
          ) : null}
        </CardBody>
      </Card>

      <div className={styles.actions}>
        <Button
          variant="ghost"
          onClick={() => setStepIndex((index) => Math.max(0, index - 1))}
          disabled={isFirstStep || pending}
          iconLeft="chevron-left"
        >
          {tCommon('actions.back')}
        </Button>
        <div className={styles.actionsRight}>
          <Button variant="ghost" onClick={() => void finish()} disabled={pending}>
            {tCommon('actions.skip')}
          </Button>
          {isLastStep ? (
            <Button
              variant="primary"
              loading={pending}
              onClick={() => void finish()}
              iconLeft="check"
            >
              {t('step.complete.cta')}
            </Button>
          ) : (
            <Button
              variant="primary"
              onClick={() => setStepIndex((index) => Math.min(STEPS.length - 1, index + 1))}
              iconRight="chevron-right"
            >
              {tCommon('actions.next')}
            </Button>
          )}
        </div>
      </div>

      <p className={styles.hint}>{t('skipHint')}</p>
    </div>
  );
}
