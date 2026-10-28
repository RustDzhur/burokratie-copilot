'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Badge } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { RelationshipItem } from '@/lib/api/types';

/** The documents linked to a contract or an invoice, each with why and, for a suggestion, the person's decision. */
export function RelationshipList({ items, onChanged }: { readonly items: readonly RelationshipItem[]; readonly onChanged: () => void }) {
  const t = useTranslations('entityMemory');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  const decide = async (id: string, confirm: boolean): Promise<void> => {
    setBusy(true);
    try {
      await (confirm ? api.memory.confirmRelationship(id) : api.memory.rejectRelationship(id));
      onChanged();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  if (items.length === 0) return <p>{t('noRelationships')}</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
      {items.map((item) => (
        <li key={item.id} data-testid="relationship">
          {item.documentId ? <Link href={`/documents/${item.documentId}`}>{item.documentName ?? t('openDocument')}</Link> : item.type}{' '}
          <Badge tone={item.status === 'CONFIRMED' ? 'success' : item.status === 'REJECTED' ? 'neutral' : 'warning'}>
            {t(`status.${item.status}` as 'status.CONFIRMED')}
          </Badge>
          {item.reason ? <small> · {item.reason}</small> : null}{' '}
          {item.status === 'SUGGESTED' ? (
            <>
              <Button variant="secondary" loading={busy} onClick={() => void decide(item.id, true)}>
                {t('confirm')}
              </Button>{' '}
              <Button variant="secondary" loading={busy} onClick={() => void decide(item.id, false)}>
                {t('reject')}
              </Button>
            </>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
