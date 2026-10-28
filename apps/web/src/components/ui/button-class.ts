import styles from './button.module.css';

/**
 * The button's class factory, in a module of its own.
 *
 * <p>It lives here rather than beside the component because it is a pure function and the component is a
 * client component. A server component that imported it from the client module made Next fail at runtime
 * with "Attempted to call buttonClass() from the server but buttonClass is on the client" - which no test,
 * no lint rule and no build caught, because the call is only impossible at runtime. The landing page and the
 * not-found page both import it, and both are server components.</p>
 *
 * <p>The rule this keeps: a module marked "use client" is a boundary, and everything it exports crosses it.
 * A pure helper does not need to cross it, so it does not live there.</p>
 */

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'neon';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonClassOptions {
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
  readonly fullWidth?: boolean;
  readonly loading?: boolean;
}

/**
 * Class factory for elements that must look like a button but are not one
 * (links, file inputs). Keeps the visual language in one place.
 */
export function buttonClass(options: ButtonClassOptions = {}): string {
  const { variant = 'secondary', size = 'md', fullWidth = false, loading = false } = options;
  return [
    styles.button,
    styles[`variant-${variant}`],
    styles[`size-${size}`],
    fullWidth ? styles.fullWidth : '',
    loading ? styles.loading : '',
  ]
    .filter(Boolean)
    .join(' ');
}
