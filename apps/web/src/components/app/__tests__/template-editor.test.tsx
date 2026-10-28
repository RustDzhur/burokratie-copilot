import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import { TemplateEditor } from '../template-editor';

function type(editor: HTMLElement, html: string): void {
  editor.innerHTML = html;
  editor.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('TemplateEditor', () => {
  it('inserts a field into the text, shows it in the preview with a sample value, and saves plain HTML', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(<TemplateEditor onSave={onSave} />);

    await user.type(screen.getByLabelText('Vorlage', { selector: 'input' }), 'Widerspruch Rechnung');
    const editor = screen.getByRole('textbox', { name: 'Vorlageninhalt' });
    type(editor, '<p>Sehr geehrte Damen und Herren,</p>');
    await user.click(screen.getByRole('button', { name: '{{recipient_name}}' }));

    expect(editor.textContent).toContain('{{recipient_name}}');
    await user.type(await screen.findByLabelText('{{recipient_name}}', { selector: 'input' }), 'Stadtwerke');
    expect(screen.getByTestId('template-preview')).toHaveTextContent('Stadtwerke');

    await user.click(screen.getByRole('button', { name: 'Vorlage speichern' }));
    expect(onSave).toHaveBeenCalledTimes(1);
    const draft = onSave.mock.calls[0]?.[0] as { name: string; body: string };
    expect(draft.name).toBe('Widerspruch Rechnung');
    expect(draft.body).toContain('{{recipient_name}}');
    expect(draft.body).not.toContain('Stadtwerke');
  });

  it('adds a field of its own, and refuses an invalid name, an empty name and an empty text', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(<TemplateEditor onSave={onSave} />);

    await user.click(screen.getByRole('button', { name: 'Vorlage speichern' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Bitte einen Namen angeben.');

    await user.type(screen.getByLabelText('Vorlage', { selector: 'input' }), 'X');
    await user.click(screen.getByRole('button', { name: 'Vorlage speichern' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Bitte einen Text schreiben.');

    await user.type(screen.getByLabelText('Eigenes Feld'), '9bad');
    await user.click(screen.getByRole('button', { name: 'Feld einsetzen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('beginnt mit einem Buchstaben');

    await user.clear(screen.getByLabelText('Eigenes Feld'));
    await user.type(screen.getByLabelText('Eigenes Feld'), 'aktenzeichen');
    await user.click(screen.getByRole('button', { name: 'Feld einsetzen' }));
    expect(screen.getByRole('textbox', { name: 'Vorlageninhalt' }).textContent).toContain('{{aktenzeichen}}');
    expect(onSave).not.toHaveBeenCalled();
  });

  it('removes scripts from pasted content before it can be saved or previewed', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(<TemplateEditor onSave={onSave} />);
    await user.type(screen.getByLabelText('Vorlage', { selector: 'input' }), 'X');
    type(screen.getByRole('textbox', { name: 'Vorlageninhalt' }), '<p>Text</p><script>alert(1)</script><img src=x onerror=alert(1)>');
    await user.click(screen.getByRole('button', { name: 'Vorlage speichern' }));
    expect((onSave.mock.calls[0]?.[0] as { body: string }).body).toBe('<p>Text</p>');
  });
});
