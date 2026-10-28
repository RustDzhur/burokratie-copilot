import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import { Checkbox, Input, RadioGroup, Select, Switch, Textarea } from '../form';
import { DatePicker as DatePickerComponent } from '../date-picker';

describe('Input', () => {
  it('links label, hint and error to the input', () => {
    renderWithProviders(
      <Input label="E-Mail" hint="Dienstlich" error="Ungültige Adresse" defaultValue="a@b" />,
    );

    const input = screen.getByLabelText('E-Mail');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(/Dienstlich/);
    expect(input).toHaveAccessibleDescription(/Ungültige Adresse/);
  });

  it('marks required fields for assistive technology', () => {
    renderWithProviders(<Input label="Betreff" required />);
    expect(screen.getByLabelText(/Betreff/)).toHaveAttribute('aria-required', 'true');
  });

  it('announces errors with role=alert', () => {
    renderWithProviders(<Input label="Betrag" error="Muss eine Zahl sein" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Muss eine Zahl sein');
  });

  it('accepts typed input', async () => {
    renderWithProviders(<Input label="Name" />);
    await userEvent.type(screen.getByLabelText('Name'), 'Anna');
    expect(screen.getByLabelText('Name')).toHaveValue('Anna');
  });

  it('is not editable when disabled', () => {
    renderWithProviders(<Input label="Fix" disabled defaultValue="x" />);
    expect(screen.getByLabelText('Fix')).toBeDisabled();
  });
});

describe('Textarea and Select', () => {
  it('renders a labelled textarea', async () => {
    renderWithProviders(<Textarea label="Notiz" />);
    await userEvent.type(screen.getByLabelText('Notiz'), 'Hallo');
    expect(screen.getByLabelText('Notiz')).toHaveValue('Hallo');
  });

  it('renders select options and reports changes', async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <Select
        label="Status"
        placeholder="Alle"
        onChange={onChange}
        options={[
          { value: 'open', label: 'Offen' },
          { value: 'closed', label: 'Geschlossen' },
        ]}
      />,
    );

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'closed');
    expect(onChange).toHaveBeenCalled();
  });
});

describe('Checkbox, Radio and Switch', () => {
  it('toggles a checkbox with Space', async () => {
    const onChange = vi.fn();
    renderWithProviders(<Checkbox label="Zustimmen" onChange={onChange} />);

    await userEvent.tab();
    await userEvent.keyboard(' ');
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('renders a radio group as a fieldset with a legend', () => {
    renderWithProviders(
      <RadioGroup
        legend="Kontotyp"
        name="type"
        value="private"
        onChange={() => undefined}
        options={[
          { value: 'private', label: 'Privat' },
          { value: 'company', label: 'Firma' },
        ]}
      />,
    );

    expect(screen.getByRole('group', { name: 'Kontotyp' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Privat' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Firma' })).not.toBeChecked();
  });

  it('exposes the switch role and checked state, and toggles via click and keyboard', async () => {
    const onChange = vi.fn();
    renderWithProviders(<Switch label="Hoher Kontrast" checked={false} onChange={onChange} />);

    const control = screen.getByRole('switch', { name: 'Hoher Kontrast' });
    expect(control).toHaveAttribute('aria-checked', 'false');

    await userEvent.click(control);
    expect(onChange).toHaveBeenCalledWith(true);

    control.focus();
    await userEvent.keyboard('{Enter}');
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it('keeps a disabled switch inert', async () => {
    const onChange = vi.fn();
    renderWithProviders(<Switch label="Gesperrt" checked={false} onChange={onChange} disabled />);
    await userEvent.click(screen.getByRole('switch', { name: 'Gesperrt' }));
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('DatePicker', () => {
  it('shows the German short format for an ISO value', () => {
    renderWithProviders(
      <DatePickerComponent label="Datum" value="2026-03-05" onChange={() => undefined} />,
    );
    expect(screen.getByLabelText('Datum')).toHaveValue('05.03.2026');
  });

  it('opens a calendar grid and selects a day', async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <DatePickerComponent label="Datum" value="2026-03-05" onChange={onChange} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /Heute|Datum/i }));
    const grid = screen.getByRole('grid');
    expect(grid).toBeInTheDocument();

    const day = screen.getByRole('button', { name: /10\. März 2026/ });
    await userEvent.click(day);
    expect(onChange).toHaveBeenCalledWith('2026-03-10');
  });
});
