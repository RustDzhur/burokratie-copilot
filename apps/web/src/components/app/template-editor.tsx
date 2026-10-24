'use client';

import { useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/form';
import { useTranslations } from '@/i18n/provider';
import { CONTRACT_TEMPLATE_FIELD_GROUPS } from '@/lib/api/wire/misc';
import { fillPlaceholders, placeholderNames, sanitizeTemplateHtml } from '@/lib/templates/sanitize';

/** The fields most letters need; a person can add their own beside them. */
export const STANDARD_FIELDS = ['recipient_name', 'recipient_address', 'date', 'reference', 'subject', 'sender_name', 'amount'] as const;

/** The template kinds the editor offers; LETTER is the default and CONTRACT draws from the contract catalogue. */
export const TEMPLATE_TYPES = ['LETTER', 'CONTRACT'] as const;

export interface TemplateDraft {
  readonly name: string;
  readonly templateType: string;
  readonly body: string;
}

/**
 * A template written like a letter: type the text, format it with the toolbar, insert the fields from the panel,
 * and see the result with sample values beside it. The result is plain HTML with {{placeholders}}; nobody writes
 * JSON or markup by hand.
 */
export function TemplateEditor({ onSave, busy = false }: { readonly onSave: (draft: TemplateDraft) => void; readonly busy?: boolean }) {
  const t = useTranslations('templates');
  const editor = useRef<HTMLDivElement>(null);
  const [name, setName] = useState('');
  const [type, setType] = useState('LETTER');
  const [custom, setCustom] = useState('');
  const [html, setHtml] = useState('');
  const [samples, setSamples] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState('');

  const read = (): void => setHtml(sanitizeTemplateHtml(editor.current?.innerHTML ?? ''));

  const format = (command: string, value?: string): void => {
    editor.current?.focus();
    document.execCommand(command, false, value);
    read();
  };

  const insert = (field: string): void => {
    const node = editor.current;
    if (!node) return;
    node.focus();
    const selection = window.getSelection();
    const token = document.createTextNode(`{{${field}}}`);
    if (selection && selection.rangeCount > 0 && node.contains(selection.getRangeAt(0).commonAncestorContainer)) {
      const range = selection.getRangeAt(0);
      range.deleteContents();
      range.insertNode(token);
      range.setStartAfter(token);
      range.collapse(true);
      selection.removeAllRanges();
      selection.addRange(range);
    } else {
      node.appendChild(token);
    }
    read();
  };

  const addCustom = (): void => {
    const key = custom.trim().replace(/[^A-Za-z0-9_]/g, '_');
    if (!/^[A-Za-z]/.test(key)) {
      setProblem(t('visual.fieldInvalid'));
      return;
    }
    setProblem('');
    insert(key);
    setCustom('');
  };

  /** The title of a contract field group, by its source; typed so the keys stay in the catalogue. */
  const sourceTitle = (source: string): string => {
    switch (source) {
      case 'VERTRAG':
        return t('editor.source.vertrag');
      case 'PARTEI':
        return t('editor.source.partei');
      case 'WERK':
        return t('editor.source.werk');
      case 'KUNDE':
        return t('editor.source.kunde');
      default:
        return source;
    }
  };

  const save = (): void => {
    if (name.trim().length === 0) {
      setProblem(t('visual.nameRequired'));
      return;
    }
    if (html.replace(/<[^>]+>/g, '').trim().length === 0) {
      setProblem(t('visual.bodyRequired'));
      return;
    }
    setProblem('');
    onSave({ name: name.trim(), templateType: type, body: html });
  };

  const used = placeholderNames(html);

  return (
    <div data-testid="template-editor">
      <Input label={t('column.name')} value={name} onChange={(event) => setName(event.target.value)} required />
      <Select
        label={t('column.type')}
        value={type}
        onChange={(event) => setType(event.target.value)}
        options={TEMPLATE_TYPES.map((value) => ({
          value,
          label: value === 'CONTRACT' ? t('type.contract') : t('type.letter'),
        }))}
      />

      <div role="toolbar" aria-label={t('visual.toolbar')} style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', margin: '0.75rem 0' }}>
        <Button variant="secondary" onClick={() => format('bold')}>
          {t('visual.bold')}
        </Button>
        <Button variant="secondary" onClick={() => format('italic')}>
          {t('visual.italic')}
        </Button>
        <Button variant="secondary" onClick={() => format('formatBlock', 'h2')}>
          {t('visual.heading')}
        </Button>
        <Button variant="secondary" onClick={() => format('insertUnorderedList')}>
          {t('visual.list')}
        </Button>
      </div>

      <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(16rem, 1fr))' }}>
        <div>
          <div
            ref={editor}
            role="textbox"
            aria-multiline="true"
            aria-label={t('editor.title')}
            contentEditable
            suppressContentEditableWarning
            onInput={read}
            onBlur={read}
            style={{ minHeight: '14rem', border: '1px solid var(--border-subtle, #999)', padding: '0.75rem', borderRadius: '0.5rem' }}
          />
          <section aria-labelledby="fields-title">
            <h3 id="fields-title">{t('editor.placeholders')}</h3>
            <p>
              <small>{t('editor.placeholderHint')}</small>
            </p>
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              {STANDARD_FIELDS.map((field) => (
                <Button key={field} variant="ghost" onClick={() => insert(field)}>
                  {`{{${field}}}`}
                </Button>
              ))}
            </div>
            {type === 'CONTRACT' ? (
              <div data-testid="contract-fields">
                {CONTRACT_TEMPLATE_FIELD_GROUPS.map((group) => (
                  <section key={group.source} aria-labelledby={`fields-${group.source}`}>
                    <h4 id={`fields-${group.source}`}>{sourceTitle(group.source)}</h4>
                    <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                      {group.fields.map((field) => (
                        <Button key={field} variant="ghost" onClick={() => insert(`${group.prefix}.${field}`)}>
                          {`{{${group.prefix}.${field}}}`}
                        </Button>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            ) : null}
            <Input label={t('visual.customField')} value={custom} onChange={(event) => setCustom(event.target.value)} />
            <Button variant="secondary" onClick={addCustom}>
              {t('visual.addField')}
            </Button>
          </section>
        </div>

        <section aria-labelledby="preview-title">
          <h3 id="preview-title">{t('editor.preview')}</h3>
          {used.map((field) => (
            <Input
              key={field}
              label={`{{${field}}}`}
              value={samples[field] ?? ''}
              onChange={(event) => setSamples((current) => ({ ...current, [field]: event.target.value }))}
            />
          ))}
          <div data-testid="template-preview" dangerouslySetInnerHTML={{ __html: sanitizeTemplateHtml(fillPlaceholders(html, samples)) }} />
        </section>
      </div>

      <div aria-live="polite">{problem ? <p role="alert">{problem}</p> : null}</div>
      <Button variant="primary" loading={busy} onClick={save}>
        {t('visual.save')}
      </Button>
    </div>
  );
}
