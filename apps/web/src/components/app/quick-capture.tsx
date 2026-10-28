'use client';

import { useState } from 'react';
import { Dialog } from '@/components/ui/overlay';
import { useTranslations } from '@/i18n/provider';
import { CapturePanel } from './capture-panel';

/**
 * A button on every screen that opens the camera panel in place. Photographing a letter with a phone must not mean looking
 * through the menu for the scanner first.
 */
export function QuickCapture() {
  const t = useTranslations('scan');
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={t('quick.label')}
        title={t('quick.label')}
        data-testid="quick-capture"
        style={{
          position: 'fixed',
          right: 'var(--space-5, 1.25rem)',
          bottom: 'calc(var(--space-5, 1.25rem) + 4.5rem)',
          zIndex: 40,
          minWidth: '3.5rem',
          minHeight: '3.5rem',
          borderRadius: '999px',
          border: 'none',
          padding: '0 1.1rem',
          fontWeight: 600,
          cursor: 'pointer',
          background: 'var(--color-accent, #2563eb)',
          color: 'var(--color-on-accent, #fff)',
          boxShadow: '0 4px 14px rgba(0,0,0,0.25)',
        }}
      >
        📷 {t('quick.short')}
      </button>
      <Dialog open={open} onClose={() => setOpen(false)} title={t('quick.title')} size="lg">
        <CapturePanel />
      </Dialog>
    </>
  );
}
