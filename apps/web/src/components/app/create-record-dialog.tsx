'use client';

import { useCallback, useState, type FormEvent, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/overlay';
import { Input, Select, Textarea } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from './create-record-dialog.module.css';

export type FormFieldType = 'text' | 'number' | 'date' | 'select' | 'textarea' | 'email';

export interface FormFieldDefinition {
  readonly name: string;
  readonly label: string;
  readonly type?: FormFieldType;
  readonly required?: boolean;
  readonly hint?: string;
  readonly options?: readonly { readonly value: string; readonly label: string }[];
  readonly placeholder?: string;
}

export interface CreateRecordDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  readonly description?: string;
  readonly submitLabel: string;
  readonly fields: readonly FormFieldDefinition[];
  /** Performs the actual API call; errors are mapped to the shared state machine. */
  readonly onSubmit: (values: Record<string, string>) => Promise<unknown>;
  readonly successMessage?: string;
  readonly onCreated?: () => void;
}

/**
 * Small, typed create/edit form in a dialog.
 *
 * Validation is intentionally thin: the backend owns the business rules
 * (docs/task.md §33: no duplicated business rules on the client), while required
 * attributes and server-side field errors (422) are surfaced on the right field.
 */
export function CreateRecordDialog({
  open,
  onClose,
  title,
  description,
  submitLabel,
  fields,
  onSubmit,
  successMessage,
  onCreated,
}: CreateRecordDialogProps) {
  const t = useTranslations('common');
  const tStates = useTranslations('states');
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const [done, setDone] = useState(false);

  const setValue = useCallback((name: string, value: string) => {
    setValues((current) => ({ ...current, [name]: value }));
  }, []);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await onSubmit(values);
      setDone(true);
      setValues({});
      onCreated?.();
      window.setTimeout(() => {
        setDone(false);
        onClose();
      }, 600);
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={pending}>
            {t('actions.cancel')}
          </Button>
          <Button type="submit" form="create-record-form" variant="primary" loading={pending}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <form id="create-record-form" className={styles.form} onSubmit={handleSubmit} noValidate>
        {error ? (
          <InlineAlert tone="danger" title={tStates('error.title')}>
            {error.fieldErrors
              ? Object.entries(error.fieldErrors)
                  .map(([field, message]) => `${field}: ${message}`)
                  .join(' · ')
              : error.code}
          </InlineAlert>
        ) : null}
        {done ? (
          <InlineAlert tone="success">{successMessage ?? tStates('success.created')}</InlineAlert>
        ) : null}
        {fields.map((field) => {
          const fieldError = error?.fieldErrors?.[field.name];
          if (field.type === 'select') {
            return (
              <Select
                key={field.name}
                label={field.label}
                hint={field.hint}
                error={fieldError}
                required={field.required}
                placeholder={field.placeholder ?? t('labels.none')}
                options={field.options ?? []}
                value={values[field.name] ?? ''}
                onChange={(event) => setValue(field.name, event.target.value)}
              />
            );
          }
          if (field.type === 'textarea') {
            return (
              <Textarea
                key={field.name}
                label={field.label}
                hint={field.hint}
                error={fieldError}
                required={field.required}
                placeholder={field.placeholder}
                value={values[field.name] ?? ''}
                onChange={(event) => setValue(field.name, event.target.value)}
              />
            );
          }
          return (
            <Input
              key={field.name}
              label={field.label}
              hint={field.hint}
              error={fieldError}
              required={field.required}
              placeholder={field.placeholder}
              type={
                field.type === 'number'
                  ? 'number'
                  : field.type === 'date'
                    ? 'date'
                    : field.type === 'email'
                      ? 'email'
                      : 'text'
              }
              value={values[field.name] ?? ''}
              onChange={(event) => setValue(field.name, event.target.value)}
            />
          );
        })}
      </form>
    </Dialog>
  );
}

export interface ToolbarProps {
  readonly children: ReactNode;
}

export function Toolbar({ children }: ToolbarProps) {
  return <div className={styles.toolbar}>{children}</div>;
}
