'use client';

import { useCallback, useState } from 'react';
import { PageHeader, PageSection } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { RadioGroup, Switch } from '@/components/ui/form';
import { InlineAlert, LiveRegion } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useAppearance } from '@/settings/appearance-provider';
import type { AccessibilityPreferences } from '@/lib/api/types';
import styles from './accessibility.module.css';

interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
}

type SpeechWindow = Window & {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
};

/**
 * Accessibility settings (§5.2.28, §23).
 *
 * The controls apply immediately through the appearance provider (data
 * attributes on <html>, see src/settings/appearance.ts) and are additionally
 * persisted for the account. Persisting is best-effort: when the backend is
 * unreachable the local settings keep working and the screen says so.
 */
export default function AccessibilityPage() {
  const t = useTranslations('a11y');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tScreen = useTranslations('accessibilitySettings');
  const { settings, update, reset } = useAppearance();
  const toast = useToast();
  const [announcement, setAnnouncement] = useState('');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [dictating, setDictating] = useState(false);

  const speechWindow = typeof window === 'undefined' ? undefined : (window as SpeechWindow);
  const SpeechRecognitionCtor =
    speechWindow?.SpeechRecognition ?? speechWindow?.webkitSpeechRecognition;

  const saveToAccount = useCallback(async () => {
    setSaving(true);
    setSaveError(null);
    const preferences: AccessibilityPreferences = {
      theme: settings.theme,
      contrast: settings.contrast,
      textScale: settings.textScale,
      motion: settings.motion,
      effects: settings.effects,
      colorCues: settings.colorCues,
      density: settings.density,
    };
    try {
      await api.settings.saveAccessibility(preferences);
      toast.success(tCommon('actions.saveChanges'));
    } catch (error) {
      setSaveError(toApiError(error).kind);
    } finally {
      setSaving(false);
    }
  }, [settings, tCommon, toast]);

  const startDictation = (): void => {
    if (!SpeechRecognitionCtor) return;
    const recognition = new SpeechRecognitionCtor();
    recognition.lang = 'de-DE';
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.onresult = (event) => {
      const transcript = event.results[0]?.[0]?.transcript ?? '';
      setAnnouncement(transcript);
    };
    recognition.start();
    setDictating(true);
    window.setTimeout(() => setDictating(false), 4000);
  };

  return (
    <>
      <PageHeader
        title={tNav('items.accessibilitySettings')}
        subtitle={tScreen('subtitle')}
        actions={
          <>
            <Button variant="ghost" iconLeft="refresh" onClick={reset}>
              {t('reset')}
            </Button>
            <Button
              variant="primary"
              iconLeft="check"
              loading={saving}
              onClick={() => void saveToAccount()}
            >
              {tCommon('actions.save')}
            </Button>
          </>
        }
      />

      {saveError ? <InlineAlert tone="warning" title={tScreen('storage.notice')} /> : null}

      <div className={styles.grid}>
        <Card>
          <CardHeader
            title={t('theme.label')}
            description={t('theme.help')}
            icon="sun"
            headingLevel={2}
          />
          <CardBody>
            <RadioGroup
              legend={t('theme.label')}
              name="theme"
              orientation="horizontal"
              value={settings.theme}
              onChange={(value) => update({ theme: value as 'light' | 'dark' | 'system' })}
              options={[
                { value: 'light', label: tNav('themeLight') },
                { value: 'dark', label: tNav('themeDark') },
                { value: 'system', label: tNav('themeSystem') },
              ]}
            />
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title={t('textScale.label')}
            description={t('textScale.help')}
            icon="layout"
            headingLevel={2}
          />
          <CardBody>
            <RadioGroup
              legend={t('textScale.label')}
              name="textScale"
              orientation="horizontal"
              value={String(settings.textScale)}
              onChange={(value) => update({ textScale: Number(value) as 100 | 112 | 125 })}
              options={[
                { value: '100', label: t('textScale.default') },
                { value: '112', label: t('textScale.large') },
                { value: '125', label: t('textScale.larger') },
              ]}
            />
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title={t('contrast.label')}
            description={t('contrast.help')}
            icon="shield"
            headingLevel={2}
          />
          <CardBody>
            <div className={styles.switches}>
              <Switch
                label={t('contrast.label')}
                description={t('contrast.help')}
                checked={settings.contrast === 'high'}
                onChange={(checked) => update({ contrast: checked ? 'high' : 'normal' })}
              />
              <Switch
                label={t('motion.label')}
                description={t('motion.help')}
                checked={settings.motion === 'reduced'}
                onChange={(checked) => update({ motion: checked ? 'reduced' : 'auto' })}
              />
              <Switch
                label={t('effects.label')}
                description={t('effects.help')}
                checked={settings.effects === 'reduced'}
                onChange={(checked) => update({ effects: checked ? 'reduced' : 'full' })}
              />
              <Switch
                label={t('colorCues.label')}
                description={t('colorCues.help')}
                checked={settings.colorCues === 'patterns'}
                onChange={(checked) => update({ colorCues: checked ? 'patterns' : 'off' })}
              />
            </div>
          </CardBody>
        </Card>

        <Card>
          <CardHeader
            title={t('density.label')}
            description={tScreen('preview.description')}
            icon="grid"
            headingLevel={2}
          />
          <CardBody>
            <RadioGroup
              legend={t('density.label')}
              name="density"
              orientation="horizontal"
              value={settings.density}
              onChange={(value) => update({ density: value as 'comfortable' | 'compact' })}
              options={[
                { value: 'comfortable', label: t('density.comfortable') },
                { value: 'compact', label: t('density.compact') },
              ]}
            />
          </CardBody>
        </Card>
      </div>

      <PageSection title={tScreen('keyboard.title')} description={tScreen('keyboard.description')}>
        <Card>
          <CardBody>
            <dl className={styles.keyList}>
              <div>
                <dt>{tScreen('keyboard.skip')}</dt>
                <dd>
                  <kbd>Tab</kbd>
                </dd>
              </div>
              <div>
                <dt>{tScreen('keyboard.commandPalette')}</dt>
                <dd>
                  <kbd>Ctrl</kbd> + <kbd>K</kbd>
                </dd>
              </div>
              <div>
                <dt>{tScreen('keyboard.dialogClose')}</dt>
                <dd>
                  <kbd>Esc</kbd>
                </dd>
              </div>
              <div>
                <dt>{tScreen('keyboard.tabs')}</dt>
                <dd>
                  <kbd>←</kbd> <kbd>→</kbd>
                </dd>
              </div>
              <div>
                <dt>{tScreen('keyboard.table')}</dt>
                <dd>
                  <kbd>Enter</kbd>
                </dd>
              </div>
            </dl>
          </CardBody>
        </Card>
      </PageSection>

      <PageSection
        title={tScreen('screenreader.title')}
        description={tScreen('screenreader.description')}
      >
        <Card>
          <CardBody>
            <div className={styles.announceRow}>
              <Button
                variant="secondary"
                iconLeft="bell"
                onClick={() => setAnnouncement(tScreen('screenreader.triggered'))}
              >
                {tScreen('screenreader.trigger')}
              </Button>
              <p className={styles.announceValue}>
                {tScreen('screenreader.lastAnnouncement')}: {announcement || '—'}
              </p>
            </div>
            <LiveRegion message={announcement} politeness="polite" />
          </CardBody>
        </Card>
      </PageSection>

      <PageSection title={tScreen('voice.title')} description={tScreen('voice.description')}>
        <Card>
          <CardBody>
            {SpeechRecognitionCtor ? (
              <Button
                variant="secondary"
                iconLeft="mic"
                onClick={startDictation}
                disabled={dictating}
              >
                {dictating ? tScreen('voice.stop') : tScreen('voice.start')}
              </Button>
            ) : (
              <InlineAlert tone="info">{tScreen('voice.unsupported')}</InlineAlert>
            )}
          </CardBody>
        </Card>
      </PageSection>

      <InlineAlert tone="info">{tScreen('storage.notice')}</InlineAlert>
    </>
  );
}
