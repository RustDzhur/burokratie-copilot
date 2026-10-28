'use client';

import { comfortAllowed } from '@/consent/consent';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button, IconButton } from '@/components/ui/button';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import styles from './iris-panel.module.css';

/** A proposal the person has to confirm before the tool runs. */
interface Proposal {
  readonly id: string;
  readonly tool: string | null;
  readonly preview: string | null;
  readonly status: 'pending' | 'busy' | 'confirmed' | 'rejected';
  readonly note?: string | null;
}

interface Message {
  readonly role: 'user' | 'assistant';
  readonly text: string;
  readonly proposal?: Proposal;
  readonly result?: Record<string, unknown> | null;
}

const STORAGE_KEY = 'bc.iris.conversation';
const SUGGESTIONS = ['suggestion.upload', 'suggestion.mail', 'suggestion.price'] as const;

function restore(): Message[] {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as Message[];
    // A proposal from an earlier tab session is no longer confirmable; it becomes plain text.
    return parsed
      .slice(-20)
      .map((message) => ({ ...message, proposal: undefined }))
      .filter((message) => message.text);
  } catch {
    return [];
  }
}

export interface IrisPanelProps {
  /** Controlled by the topbar button: the panel is rendered only while open. */
  readonly open: boolean;
  readonly onClose: () => void;
}

/**
 * Iris, the assistant that operates the platform. The panel is controlled by the topbar: the button
 * there opens it as a dropdown under the header (a sheet on small screens), and this component renders
 * the chat. Every tool with an effect comes back as a proposal with a preview and the buttons
 * "Bestätigen"/"Ablehnen": nothing happens without a person. The conversation lives only in this tab.
 */
export function IrisPanel({ open, onClose }: IrisPanelProps) {
  const t = useTranslations('iris');
  const [messages, setMessages] = useState<readonly Message[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    setMessages(restore());
  }, []);

  useEffect(() => {
    try {
      if (comfortAllowed()) window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-20)));
    } catch {
      // The conversation simply is not kept when the browser refuses storage.
    }
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages, open]);

  const send = async (text: string): Promise<void> => {
    const question = text.trim();
    if (!question || busy) return;
    const next: Message[] = [...messages, { role: 'user', text: question }];
    setMessages(next);
    setDraft('');
    setBusy(true);
    setNote(null);
    try {
      const answer = await api.assistantTools.ask({ question });
      if (answer.kind === 'PROPOSAL' && answer.proposalId) {
        setMessages([
          ...next,
          {
            role: 'assistant',
            text: answer.message,
            proposal: {
              id: answer.proposalId,
              tool: answer.tool ?? null,
              preview: answer.preview ?? null,
              status: 'pending',
            },
          },
        ]);
      } else if (answer.kind === 'REFUSED') {
        setMessages([...next, { role: 'assistant', text: refusalText(answer.code, answer.message) }]);
      } else {
        setMessages([...next, { role: 'assistant', text: answer.message, result: answer.result }]);
      }
    } catch {
      setNote(t('unavailable.other'));
    } finally {
      setBusy(false);
    }
  };

  const refusalText = (code: string | null | undefined, fallback: string): string => {
    if (code === 'NOT_CONFIGURED' || code === 'DAILY_LIMIT' || code === 'MODEL_FAILED') {
      return t(`unavailable.${code}` as 'unavailable.other');
    }
    return fallback || t('unavailable.other');
  };

  const patchMessage = (
    index: number,
    patch: { readonly proposal?: Partial<Proposal>; readonly result?: Record<string, unknown> | null },
  ): void => {
    setMessages((current) =>
      current.map((message, at) => {
        if (at !== index || !message.proposal) return message;
        return {
          ...message,
          ...(patch.proposal ? { proposal: { ...message.proposal, ...patch.proposal } } : {}),
          ...(patch.result !== undefined ? { result: patch.result } : {}),
        };
      }),
    );
  };

  const confirm = async (index: number, proposal: Proposal): Promise<void> => {
    patchMessage(index, { proposal: { status: 'busy', note: null } });
    try {
      const done = await api.assistantTools.confirm(proposal.id);
      patchMessage(index, { proposal: { status: 'confirmed' }, result: done.result });
    } catch {
      patchMessage(index, { proposal: { status: 'pending', note: t('proposal.failed') } });
    }
  };

  const reject = async (index: number, proposal: Proposal): Promise<void> => {
    patchMessage(index, { proposal: { status: 'busy', note: null } });
    try {
      await api.assistantTools.reject(proposal.id);
      patchMessage(index, { proposal: { status: 'rejected' } });
    } catch {
      patchMessage(index, { proposal: { status: 'pending', note: t('proposal.failed') } });
    }
  };

  const submit = (event: FormEvent): void => {
    event.preventDefault();
    void send(draft);
  };

  const resultBlock = (result: Record<string, unknown> | null | undefined): string => {
    if (!result) return '';
    try {
      return JSON.stringify(result, null, 2);
    } catch {
      return String(result);
    }
  };

  if (!open) return null;

  return (
    <section className={styles.panel} id="iris-panel" role="dialog" aria-label={t('name')}>
      <header className={styles.header}>
        <div>
          <h2 className={styles.title}>{t('name')}</h2>
          <p className={styles.subtitle}>{t('subtitle')}</p>
        </div>
        <IconButton label={t('close')} icon="x" size="sm" onClick={onClose} />
      </header>

      <ul className={styles.messages} ref={listRef} aria-live="polite">
        <li className={`${styles.bubble} ${styles.assistant}`}>{t('greeting')}</li>
        {messages.length === 0 ? (
          <li>
            <div className={styles.chips}>
              {SUGGESTIONS.map((key) => (
                <button key={key} type="button" className={styles.chip} onClick={() => void send(t(key))}>
                  {t(key)}
                </button>
              ))}
            </div>
          </li>
        ) : null}
        {messages.map((message, index) => (
          <li key={`${index}-${message.role}`} className={styles.messageItem}>
            <div className={`${styles.bubble} ${message.role === 'user' ? styles.user : styles.assistant}`}>
              {message.text}
            </div>
            {message.proposal ? (
              <div className={styles.proposal}>
                {message.proposal.preview ? (
                  <pre className={styles.preview}>{message.proposal.preview}</pre>
                ) : null}
                {message.proposal.status === 'pending' || message.proposal.status === 'busy' ? (
                  <div className={styles.proposalActions}>
                    <Button
                      size="sm"
                      variant="primary"
                      disabled={message.proposal.status === 'busy'}
                      onClick={() => void confirm(index, message.proposal as Proposal)}
                    >
                      {t('proposal.confirm')}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={message.proposal.status === 'busy'}
                      onClick={() => void reject(index, message.proposal as Proposal)}
                    >
                      {t('proposal.reject')}
                    </Button>
                  </div>
                ) : null}
                {message.proposal.status === 'confirmed' ? (
                  <p className={styles.proposalDone}>{t('proposal.confirmed')}</p>
                ) : null}
                {message.proposal.status === 'rejected' ? (
                  <p className={styles.proposalDone}>{t('proposal.rejected')}</p>
                ) : null}
                {message.proposal.note ? <p className={styles.note}>{message.proposal.note}</p> : null}
              </div>
            ) : null}
            {message.result ? <pre className={styles.result}>{resultBlock(message.result)}</pre> : null}
          </li>
        ))}
        {busy ? <li className={`${styles.bubble} ${styles.assistant}`}>{t('thinking')}</li> : null}
        {note ? <li className={styles.note}>{note}</li> : null}
      </ul>

      <form className={styles.form} onSubmit={submit}>
        <input
          className={styles.input}
          value={draft}
          maxLength={1000}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={t('placeholder')}
          aria-label={t('placeholder')}
        />
        <Button type="submit" variant="primary" disabled={busy || draft.trim().length === 0}>
          {t('send')}
        </Button>
      </form>
    </section>
  );
}
