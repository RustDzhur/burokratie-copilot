import { render, type RenderOptions, type RenderResult } from '@testing-library/react';
import { axe, type AxeResults } from 'jest-axe';
import type { ReactElement, ReactNode } from 'react';
import { expect } from 'vitest';
import { ToastProvider } from '@/components/ui/toast';
import { defaultLocale, type Locale } from '@/i18n/config';
import { I18nProvider } from '@/i18n/provider';
import { AppearanceProvider } from '@/settings/appearance-provider';

export interface ProviderOptions extends Omit<RenderOptions, 'wrapper'> {
  readonly locale?: Locale;
}

/**
 * Renders a component inside the real application providers (locale, appearance,
 * toasts) so tests exercise the shipped wiring instead of a simplified harness.
 */
export function renderWithProviders(ui: ReactElement, options: ProviderOptions = {}): RenderResult {
  const { locale = defaultLocale, ...renderOptions } = options;
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <I18nProvider initialLocale={locale}>
      <AppearanceProvider>
        <ToastProvider>{children}</ToastProvider>
      </AppearanceProvider>
    </I18nProvider>
  );
  return render(ui, { wrapper: Wrapper, ...renderOptions });
}

/** Runs axe and fails with the readable list of violations. */
export async function expectNoA11yViolations(container: HTMLElement): Promise<AxeResults> {
  const results = await axe(container, {
    rules: {
      // jsdom cannot compute layout, so colour contrast is verified separately by
      // src/design-system/__tests__/contrast.test.ts against the shipped tokens.
      'color-contrast': { enabled: false },
    },
  });
  const summary = results.violations.map(
    (violation) => `${violation.id} (${violation.impact ?? 'n/a'}): ${violation.help}`,
  );
  expect(summary).toEqual([]);
  return results;
}

export * from '@testing-library/react';
export { default as userEvent } from '@testing-library/user-event';
