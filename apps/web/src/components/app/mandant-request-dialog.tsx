'use client';

import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox, Input, Textarea } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { MandantRequest, MandantRequestMissing } from '@/lib/api/types';
import styles from './mandant-request.module.css';

/**
 * The letter that asks a client for missing vouchers (Kanzlei edition, §5.7).
 *
 * <p>The text is produced by the server from the client and the voucher, shown here and editable. Sending
 * is two API calls on purpose: first the draft is stored, then it is handed to the mail mechanism. If the
 * deployment has no postausgang, the second call answers 409 and the draft stays — a dialog that closed
 * with "sent" while nothing left the house is the failure this order prevents.</p>
 *
 * <p>A retry reuses the draft it already created instead of writing a second one: a person who tries again
 * after configuring mail must not leave two letters in the history.</p>
 */
export interface MandantRequestDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  /** The client the letter goes to. The dialog is not offered without one. */
  readonly mandantId: string;
  /** The voucher the request is about, when it comes from a Beleg card. */
  readonly documentId?: string | null;
  readonly onSent?: (request: MandantRequest) => void;
}

const MISSING: readonly MandantRequestMissing[] = ['AMOUNT', 'DATE', 'NUMBER', 'ATTACHMENT'];

export function MandantRequestDialog({
  open,
  onClose,
  mandantId,
  documentId = null,
  onSent,
}: MandantRequestDialogProps) {
  const t = useTranslations('anfrage');
  const toast = useToast();

  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [missing, setMissing] = useState<readonly MandantRequestMissing[]>([]);
  const [recipient, setRecipient] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const missingKey = missing.join(',');

  // The selection changes the sentence, so the preview is generated again. The person's edits are
  // overwritten only when they themselves change what is missing, never by a background re-render.
  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    setLoading(true);
    setError(null);
    api.mandantRequests
      .preview({ mandantId, documentId, missing })
      .then((letter) => {
        if (!active) return;
        setSubject(letter.subject);
        setBody(letter.body);
        setRecipient(
          letter.mandantEmail
            ? `${letter.mandantName} · ${letter.mandantEmail}`
            : letter.mandantName,
        );
      })
      .catch(() => {
        if (!active) return;
        setError(t('loadError'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, mandantId, documentId, missingKey]);

  useEffect(() => {
    if (!open) {
      setDraftId(null);
      setError(null);
      setMissing([]);
    }
  }, [open]);

  const toggle = (item: MandantRequestMissing): void => {
    setMissing((current) =>
      current.includes(item) ? current.filter((entry) => entry !== item) : [...current, item],
    );
  };

  const send = async (): Promise<void> => {
    if (subject.trim() === '' || body.trim() === '') return;
    setSending(true);
    setError(null);
    try {
      let id = draftId;
      if (id === null) {
        const created = await api.mandantRequests.create({
          mandantId,
          documentId,
          missing,
          subject: subject.trim(),
          body: body.trim(),
        });
        id = created.id;
        setDraftId(id);
      } else {
        // The draft of a previous attempt is edited rather than duplicated.
        await api.mandantRequests.update(id, { subject: subject.trim(), body: body.trim() });
      }
      const sent = await api.mandantRequests.send(id);
      toast.success(t('sent'));
      onSent?.(sent);
      setDraftId(null);
      onClose();
    } catch (caught) {
      const apiError = toApiError(caught);
      setError(
        apiError.code === 'MAIL_NOT_CONFIGURED'
          ? t('mailNotConfigured')
          : apiError.code === 'MANDANT_EMAIL_MISSING'
            ? t('noEmail')
            : t('sendError'),
      );
    } finally {
      setSending(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('title')}
      description={t('description')}
      size="lg"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={sending}>
            {t('cancel')}
          </Button>
          <Button
            variant="primary"
            iconLeft="send"
            loading={sending}
            disabled={loading || subject.trim() === '' || body.trim() === ''}
            onClick={() => void send()}
          >
            {t('send')}
          </Button>
        </>
      }
    >
      {recipient ? (
        <p className={styles.recipient}>
          <span>{t('recipient')}</span> <strong>{recipient}</strong>
        </p>
      ) : null}

      <fieldset className={styles.missing}>
        <legend>{t('missing')}</legend>
        <p className={styles.hint}>{t('missingHint')}</p>
        <div className={styles.missingOptions}>
          {MISSING.map((item) => (
            <Checkbox
              key={item}
              label={t(`missing.${item}`)}
              checked={missing.includes(item)}
              onChange={() => toggle(item)}
            />
          ))}
        </div>
      </fieldset>

      {loading ? <p className={styles.hint}>{t('loading')}</p> : null}

      {error ? (
        <InlineAlert tone="danger" title={t('errorTitle')}>
          {error}
        </InlineAlert>
      ) : null}

      <Input
        label={t('subject')}
        value={subject}
        onChange={(event) => setSubject(event.target.value)}
        disabled={loading}
        maxLength={500}
      />
      <Textarea
        label={t('body')}
        value={body}
        onChange={(event) => setBody(event.target.value)}
        rows={14}
        disabled={loading}
      />
      <p className={styles.hint}>{t('draftHint')}</p>
    </Dialog>
  );
}
