'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { toApiError } from '@/lib/api/errors';
import { downloadBlob } from '@/lib/download';

export interface ExportButtonProps {
  readonly label: string;
  /** Performs the export request; the response is streamed straight to a file. */
  readonly onExport: () => Promise<Blob>;
  readonly filename: string;
  readonly variant?: 'secondary' | 'ghost';
}

/**
 * Export action shared by registry, archive, audit and report screens.
 *
 * Success is only reported after the backend response arrived, and failures are
 * surfaced with the localized error message (docs/task.md §29).
 */
export function ExportButton({
  label,
  onExport,
  filename,
  variant = 'secondary',
}: ExportButtonProps) {
  const toast = useToast();
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const [pending, setPending] = useState(false);

  const run = async (): Promise<void> => {
    setPending(true);
    try {
      const blob = await onExport();
      downloadBlob(blob, filename);
      toast.success(tStates('success.saved'), filename);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setPending(false);
    }
  };

  return (
    <Button variant={variant} iconLeft="download" loading={pending} onClick={() => void run()}>
      {label}
    </Button>
  );
}
