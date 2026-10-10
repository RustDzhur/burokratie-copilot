/**
 * Minimal ambient typings for `jest-axe`.
 *
 * The package ships no type declarations and `@types/jest-axe` is not a
 * dependency of this project. Only the surface used by the test suite is
 * declared, so the A11y assertions stay type-safe without adding a dependency.
 */
declare module 'jest-axe' {
  export interface AxeNodeResult {
    readonly html: string;
    readonly target: readonly string[];
    readonly failureSummary?: string;
  }

  export interface AxeViolation {
    readonly id: string;
    readonly impact?: 'minor' | 'moderate' | 'serious' | 'critical' | null;
    readonly help: string;
    readonly description: string;
    readonly helpUrl?: string;
    readonly nodes: readonly AxeNodeResult[];
  }

  export interface AxeResults {
    readonly violations: readonly AxeViolation[];
    readonly passes: readonly unknown[];
    readonly incomplete: readonly unknown[];
    readonly inapplicable: readonly unknown[];
  }

  export interface AxeRunOptions {
    readonly rules?: Record<string, { readonly enabled: boolean }>;
  }

  export function axe(html: Element | string, options?: AxeRunOptions): Promise<AxeResults>;

  export const toHaveNoViolations: unknown;
}
