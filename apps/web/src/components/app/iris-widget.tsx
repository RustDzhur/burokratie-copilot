'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Icon } from '@/components/icons';
import { Button, IconButton } from '@/components/ui/button';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { findScreenByPath } from '@/navigation/screens';
import styles from './iris-widget.module.css';

interface Message {
  readonly role: 'user' | 'assistant';
  readonly text: string;
  readonly open?: string;
}

const STORAGE_KEY = 'bc.iris.conversation';
const SUGGESTIONS = ['suggestion.upload', 'suggestion.mail', 'suggestion.price'] as const;

function restore(): Message[] {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Message[]).slice(-20) : [];
  } catch {
    return [];
  }
}

/**
 * Iris, the assistant that knows the application. A round button in the corner opens a small chat; the answer can
 * offer a button that opens the screen it talks about. The conversation lives only in this browser tab.
 */
export function IrisWidget() {
  const t = useTranslations('iris');
  const tNav = useTranslations('nav');
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
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
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-20)));
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
      const answer = await api.guide.ask({
        messages: next.map(({ role, text: content }) => ({ role, text: content })),
        page: pathname,
        locale,
      });
      if (!answer.available) {
        const key = answer.reason === 'NOT_CONFIGURED' || answer.reason === 'DAILY_LIMIT' ? answer.reason : 'other';
        setNote(t(`unavailable.${key}` as 'unavailable.other'));
      } else {
        setMessages([
          ...next,
          { role: 'assistant', text: answer.text, open: answer.open || undefined },
        ]);
        if (answer.handoff) setNote(t('handoff'));
      }
    } catch {
      setNote(t('unavailable.other'));
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: FormEvent): void => {
    event.preventDefault();
    void send(draft);
  };

  if (!open) {
    return (
      <button type="button" className={styles.fab} onClick={() => setOpen(true)} aria-label={t('open')}>
        <Icon name="sparkles" size={18} />
        <span>{t('name')}</span>
      </button>
    );
  }

  return (
    <section className={styles.panel} role="dialog" aria-label={t('name')}>
      <header className={styles.header}>
        <div>
          <h2 className={styles.title}>{t('name')}</h2>
          <p className={styles.subtitle}>{t('subtitle')}</p>
        </div>
        <IconButton label={t('close')} icon="x" size="sm" onClick={() => setOpen(false)} />
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
        {messages.map((message, index) => {
          const screen = message.open ? findScreenByPath(message.open) : undefined;
          return (
            <li key={`${index}-${message.role}`} className={styles.messageItem}>
              <div className={`${styles.bubble} ${message.role === 'user' ? styles.user : styles.assistant}`}>
                {message.text}
              </div>
              {message.open && screen ? (
                <div className={styles.open}>
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => {
                      router.push(message.open as string);
                      setOpen(false);
                    }}
                  >
                    {t('openScreen', { name: tNav(screen.titleKey) })}
                  </Button>
                </div>
              ) : null}
            </li>
          );
        })}
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
