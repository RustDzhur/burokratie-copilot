'use client';

import { useEffect, useMemo, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog, Input, RadioGroup } from '@/components/ui';
import { useTranslations } from '@/i18n/provider';
import type { ContactSummary } from '@/lib/api/types';

/**
 * "Mandant zuordnen" for a selection of vouchers (Kanzlei edition, §5.1/§5.2).
 *
 * <p>A dropdown is not enough here: an office can have hundreds of clients, and the person assigning a
 * voucher knows a name, not a position in a list. The dialog therefore searches while typing and still shows
 * the full choice with radio buttons, so the dialog is usable with a keyboard and a screen reader.</p>
 *
 * <p>Nothing is assigned from here: the screen calls the batch endpoint with the chosen client, and the server
 * decides whether each voucher may take it. Closing without a choice changes nothing.</p>
 */
export function BatchMandantDialog({
  open,
  contacts,
  count,
  busy = false,
  onClose,
  onConfirm,
}: {
  readonly open: boolean;
  readonly contacts: readonly ContactSummary[];
  /** How many vouchers the choice applies to, for the sentence in the dialog. */
  readonly count: number;
  readonly busy?: boolean;
  readonly onClose: () => void;
  readonly onConfirm: (mandantId: string) => void;
}) {
  const t = useTranslations('documents');
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState('');

  // A reopened dialog must not offer the previous choice: the next selection is a new decision.
  useEffect(() => {
    if (open) {
      setQuery('');
      setSelected('');
    }
  }, [open]);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const rows = needle === ''
      ? contacts
      : contacts.filter((contact) => contact.name.toLowerCase().includes(needle));
    // The search narrows the list; rendering an office-sized list at once would only slow the dialog down.
    return rows.slice(0, 50);
  }, [contacts, query]);

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('bulk.assign.title')}
      description={t('bulk.assign.description', { count })}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('bulk.assign.cancel')}
          </Button>
          <Button
            variant="primary"
            disabled={selected === ''}
            loading={busy}
            onClick={() => onConfirm(selected)}
          >
            {t('bulk.assign.confirm')}
          </Button>
        </>
      }
    >
      <Input
        label={t('bulk.assign.search')}
        placeholder={t('bulk.assign.searchPlaceholder')}
        value={query}
        autoFocus
        onChange={(event) => setQuery(event.target.value)}
      />
      {matches.length === 0 ? (
        <p>{t('bulk.assign.empty')}</p>
      ) : (
        <RadioGroup
          legend={t('bulk.assign.legend')}
          name="batch-mandant"
          value={selected}
          onChange={setSelected}
          options={matches.map((contact) => ({
            value: contact.id,
            label: contact.name,
            description: contact.city ?? undefined,
          }))}
        />
      )}
    </Dialog>
  );
}
