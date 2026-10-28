'use client';

import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react';
import { Icon, type IconName } from '@/components/icons';
import { Spinner } from './feedback';
import styles from './button.module.css';

// Imported for use here, and re-exported so that existing imports keep working. The definition lives in a
// module without 'use client': a server component cannot call a function that crosses that boundary.
import { buttonClass } from './button-class';
import type { ButtonClassOptions, ButtonSize, ButtonVariant } from './button-class';

export type { ButtonClassOptions, ButtonSize, ButtonVariant };

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  readonly children?: ReactNode;
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
  /** Shows a spinner, sets `aria-busy` and blocks interaction. */
  readonly loading?: boolean;
  /** Announced while loading; defaults to the visible label. */
  readonly loadingLabel?: string;
  readonly iconLeft?: IconName | Exclude<ReactNode, string>;
  readonly iconRight?: IconName | Exclude<ReactNode, string>;
  readonly fullWidth?: boolean;
  readonly ref?: Ref<HTMLButtonElement>;
}

function renderIcon(icon: IconName | ReactNode, size: number): ReactNode {
  if (typeof icon === 'string') return <Icon name={icon as IconName} size={size} />;
  return icon;
}

export function Button({
  children,
  variant = 'secondary',
  size = 'md',
  loading = false,
  loadingLabel,
  iconLeft,
  iconRight,
  fullWidth = false,
  disabled,
  type = 'button',
  className,
  ref,
  ...rest
}: ButtonProps) {
  const iconSize = size === 'sm' ? 15 : size === 'lg' ? 19 : 17;
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      className={[buttonClass({ variant, size, fullWidth, loading }), className]
        .filter(Boolean)
        .join(' ')}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      data-variant={variant}
      data-size={size}
    >
      {loading ? (
        <span className={styles.spinnerSlot}>
          <Spinner size={size === 'lg' ? 'md' : 'sm'} label={loadingLabel ?? undefined} />
        </span>
      ) : iconLeft ? (
        <span className={styles.iconSlot}>{renderIcon(iconLeft, iconSize)}</span>
      ) : null}
      {children ? <span className={styles.label}>{children}</span> : null}
      {!loading && iconRight ? (
        <span className={styles.iconSlot}>{renderIcon(iconRight, iconSize)}</span>
      ) : null}
    </button>
  );
}

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  /** Accessible name — required, rendered as `aria-label` and tooltip title. */
  readonly label: string;
  readonly icon: IconName | ReactNode;
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
  readonly loading?: boolean;
  readonly pressed?: boolean;
  readonly ref?: Ref<HTMLButtonElement>;
}

export function IconButton({
  label,
  icon,
  variant = 'ghost',
  size = 'md',
  loading = false,
  pressed,
  disabled,
  type = 'button',
  className,
  ref,
  ...rest
}: IconButtonProps) {
  const iconSize = size === 'sm' ? 15 : size === 'lg' ? 20 : 18;
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      aria-busy={loading || undefined}
      disabled={disabled || loading}
      data-variant={variant}
      data-size={size}
      className={[
        styles.iconButton,
        styles[`variant-${variant}`],
        styles[`iconSize-${size}`],
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {loading ? <Spinner size="sm" label={label} /> : renderIcon(icon, iconSize)}
    </button>
  );
}
