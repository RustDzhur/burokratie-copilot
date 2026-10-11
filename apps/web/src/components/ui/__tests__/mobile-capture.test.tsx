import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { MobileCapture } from '../mobile-capture';

/**
 * Taking a picture of a letter (§5.2.21, B-015).
 *
 * What can be checked without a telephone is that the control asks the system for the camera, that it
 * is reachable by keyboard and by a screen reader, and that what it hands on is what was chosen. What
 * cannot be checked here is the camera itself, and nothing below claims otherwise.
 */
describe('MobileCapture', () => {
  it('asks the system to open the rear camera', () => {
    render(<MobileCapture onCapture={vi.fn()} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.accept).toBe('image/*');
    // The rear camera is the one pointed at paper.
    expect(input.getAttribute('capture')).toBe('environment');
  });

  it('can be pointed at the front camera instead', () => {
    render(<MobileCapture onCapture={vi.fn()} facing="user" />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.getAttribute('capture')).toBe('user');
  });

  it('allows more than one picture, because a letter has more than one page', () => {
    render(<MobileCapture onCapture={vi.fn()} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.multiple).toBe(true);
  });

  it('offers a button that can be reached by keyboard', async () => {
    const onCapture = vi.fn();
    render(<MobileCapture onCapture={onCapture} />);

    const button = screen.getByRole('button', { name: 'Foto aufnehmen' });
    expect(button).toBeEnabled();

    // A control that cannot be reached from the keyboard is a control half the users do not have.
    await userEvent.tab();
    expect(button).toHaveFocus();
  });

  it('hands on the pictures that were taken', async () => {
    const onCapture = vi.fn();
    render(<MobileCapture onCapture={onCapture} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['x'], 'brief.png', { type: 'image/png' });
    await userEvent.upload(input, file);

    expect(onCapture).toHaveBeenCalledTimes(1);
    const handed = onCapture.mock.calls[0]?.[0] as File[];
    expect(handed).toHaveLength(1);
  });

  it('does not report anything when nothing was chosen', async () => {
    const onCapture = vi.fn();
    render(<MobileCapture onCapture={onCapture} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.upload(input, []);

    expect(onCapture).not.toHaveBeenCalled();
  });

  it('can be switched off, and a disabled control cannot be clicked', () => {
    render(<MobileCapture onCapture={vi.fn()} disabled />);

    expect(screen.getByRole('button', { name: 'Foto aufnehmen' })).toBeDisabled();
    expect((document.querySelector('input[type="file"]') as HTMLInputElement).disabled).toBe(true);
  });

  it('is not hidden from assistive technology', () => {
    render(<MobileCapture onCapture={vi.fn()} />);

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    // Clipped rather than `display: none`: a control removed from the layout that way is removed from
    // the accessibility tree too, and this one is the whole point of the page.
    expect(input.style.display).not.toBe('none');
    expect(input.getAttribute('style')).toContain('clip-path');
    // And it is not a keyboard stop, because its focus ring would be invisible.
    expect(input.getAttribute('tabindex')).toBe('-1');
  });
});
