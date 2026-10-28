'use client';

import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { Input } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { MergeGroup } from '@/lib/api/types';

interface Page {
  readonly id: string;
  readonly name: string;
  readonly keep: boolean;
}

interface Draft {
  readonly title: string;
  readonly pages: readonly Page[];
}

const toDraft = (group: MergeGroup): Draft => ({
  title: group.suggestedTitle,
  pages: group.documentIds.map((id, index) => ({ id, name: group.filenames[index] ?? id, keep: true })),
});

/**
 * Pages that were uploaded one by one but belong together (the photos of one form). The platform proposes groups; the person
 * corrects the order and the members, and merges one group or all of them. Nothing is merged without being asked.
 */
export function MergeDialog({
  open,
  onClose,
  onMerged,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onMerged: () => void;
}) {
  const t = useTranslations('documents');
  const toast = useToast();
  const [drafts, setDrafts] = useState<readonly Draft[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    setDrafts(null);
    setProblem(null);
    const controller = new AbortController();
    api.documents
      .mergeCandidates(controller.signal)
      .then((groups) => setDrafts(groups.map(toDraft)))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setProblem(toApiError(error).message);
      });
    return () => controller.abort();
  }, [open]);

  const update = (index: number, change: (draft: Draft) => Draft): void =>
    setDrafts((current) => (current ? current.map((draft, i) => (i === index ? change(draft) : draft)) : current));

  const move = (index: number, page: number, by: number): void =>
    update(index, (draft) => {
      const pages = [...draft.pages];
      const target = page + by;
      if (target < 0 || target >= pages.length) return draft;
      const moved = pages[page];
      const other = pages[target];
      if (!moved || !other) return draft;
      pages[page] = other;
      pages[target] = moved;
      return { ...draft, pages };
    });

  const mergeOne = async (draft: Draft): Promise<boolean> => {
    const ids = draft.pages.filter((page) => page.keep).map((page) => page.id);
    if (ids.length < 2) return false;
    await api.documents.merge(ids, draft.title);
    return true;
  };

  const run = async (selection: readonly number[]): Promise<void> => {
    if (!drafts) return;
    setBusy(true);
    setProblem(null);
    let merged = 0;
    try {
      for (const index of selection) {
        const draft = drafts[index];
        if (draft && (await mergeOne(draft))) merged += 1;
      }
      toast.success(t('merge.done', { count: merged }));
      onMerged();
      onClose();
    } catch (error) {
      setProblem(toApiError(error).message || t('merge.failed'));
      if (merged > 0) onMerged();
    } finally {
      setBusy(false);
    }
  };

  const usable = drafts ? drafts.map((_, index) => index) : [];

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="lg"
      title={t('merge.title')}
      description={t('merge.description')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('merge.close')}
          </Button>
          {drafts && drafts.length > 1 ? (
            <Button loading={busy} onClick={() => void run(usable)}>
              {t('merge.all', { count: drafts.length })}
            </Button>
          ) : null}
        </>
      }
    >
      {drafts === null && !problem ? <SkeletonText lines={4} /> : null}
      {problem ? <InlineAlert tone="danger">{problem}</InlineAlert> : null}
      {drafts && drafts.length === 0 ? <p>{t('merge.none')}</p> : null}
      {drafts?.map((draft, index) => (
        <section key={draft.pages.map((page) => page.id).join('-')} aria-label={draft.title} style={{ border: '1px solid var(--color-border, #ccc)', borderRadius: 10, padding: '0.75rem', marginBottom: '0.75rem' }}>
          <Input label={t('merge.name')} value={draft.title} onChange={(event) => update(index, (d) => ({ ...d, title: event.target.value }))} />
          <ol style={{ listStyle: 'decimal', paddingLeft: '1.5rem', display: 'grid', gap: '0.5rem', margin: '0.75rem 0 1rem' }}>
            {draft.pages.map((page, position) => (
              <li key={page.id} style={{ opacity: page.keep ? 1 : 0.5 }}>
                <label style={{ marginRight: '0.5rem' }}>
                  <input
                    type="checkbox"
                    checked={page.keep}
                    onChange={(event) =>
                      update(index, (d) => ({ ...d, pages: d.pages.map((p) => (p.id === page.id ? { ...p, keep: event.target.checked } : p)) }))
                    }
                  />{' '}
                  {page.name}
                </label>
                <button type="button" style={{ padding: '0 0.4rem', marginLeft: '0.25rem' }} aria-label={t('merge.up', { name: page.name })} onClick={() => move(index, position, -1)} disabled={position === 0}>
                  ↑
                </button>{' '}
                <button type="button" style={{ padding: '0 0.4rem' }} aria-label={t('merge.down', { name: page.name })} onClick={() => move(index, position, 1)} disabled={position === draft.pages.length - 1}>
                  ↓
                </button>
              </li>
            ))}
          </ol>
          <Button loading={busy} disabled={draft.pages.filter((page) => page.keep).length < 2} onClick={() => void run([index])}>
            {t('merge.one')}
          </Button>
        </section>
      ))}
    </Dialog>
  );
}
