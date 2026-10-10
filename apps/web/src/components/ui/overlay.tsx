'use client';

import {
  cloneElement,
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@/components/icons';
import { useTranslations } from '@/i18n/provider';
import { Button, IconButton } from './button';
import styles from './overlay.module.css';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Visibility check that works without layout information.
 *
 * `offsetParent` is always `null` in jsdom (and unreliable with fixed
 * positioning), so the check relies on explicit hiding attributes and computed
 * display/visibility instead.
 */
function isFocusCandidateVisible(element: HTMLElement): boolean {
  if (element.hasAttribute('hidden')) return false;
  if (element.getAttribute('aria-hidden') === 'true') return false;
  if (element.closest('[hidden]') !== null) return false;
  const view = element.ownerDocument.defaultView;
  if (view) {
    const style = view.getComputedStyle(element);
    if (style.display === 'none' || style.visibility === 'hidden') return false;
  }
  return true;
}

function getFocusable(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    isFocusCandidateVisible,
  );
}

interface ModalBehaviour {
  readonly containerRef: RefObject<HTMLDivElement | null>;
}

/**
 * Shared modal behaviour: focus trap, Escape to close, scroll lock and focus
 * restoration. Implemented (instead of the native <dialog>) so that the same
 * logic covers dialogs and drawers on every supported browser.
 */
function useModalBehaviour(open: boolean, onClose: () => void): ModalBehaviour {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const container = containerRef.current;
    const firstFocusable = container ? getFocusable(container)[0] : undefined;
    (firstFocusable ?? container)?.focus();

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      const node = containerRef.current;
      if (!node) return;
      const focusable = getFocusable(node);
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !node.contains(active))) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first?.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      document.body.style.overflow = previousOverflow;
      previouslyFocused?.focus?.();
    };
  }, [open, onClose]);

  return { containerRef };
}

export interface DialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  readonly description?: string;
  readonly children?: ReactNode;
  readonly footer?: ReactNode;
  readonly size?: 'sm' | 'md' | 'lg';
  readonly closeOnBackdrop?: boolean;
  readonly showCloseButton?: boolean;
}

/** Accessible modal dialog: `role="dialog"`, `aria-modal`, label + description. */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  closeOnBackdrop = true,
  showCloseButton = true,
}: DialogProps) {
  const t = useTranslations('a11y');
  const { containerRef } = useModalBehaviour(open, onClose);
  const titleId = useId();
  const descriptionId = useId();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  if (!open || !mounted) return null;

  return createPortal(
    <div className={styles.portal}>
      {/* Decorative click target only: dismissing with the keyboard happens via
          Escape or the close button, so the backdrop stays hidden from AT. */}
      <div
        className={styles.backdrop}
        aria-hidden="true"
        data-testid="dialog-backdrop"
        onMouseDown={() => {
          if (closeOnBackdrop) onClose();
        }}
      />
      <div className={styles.layer} aria-hidden={false}>
        <div
          ref={containerRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          aria-describedby={description ? descriptionId : undefined}
          tabIndex={-1}
          className={[styles.dialog, styles[`dialog-${size}`]].join(' ')}
        >
          <header className={styles.header}>
            <div>
              <h2 className={styles.title} id={titleId}>
                {title}
              </h2>
              {description ? (
                <p className={styles.description} id={descriptionId}>
                  {description}
                </p>
              ) : null}
            </div>
            {showCloseButton ? (
              <IconButton label={t('dialog.close')} icon="x" onClick={onClose} />
            ) : null}
          </header>
          {children ? <div className={styles.body}>{children}</div> : null}
          {footer ? <footer className={styles.footer}>{footer}</footer> : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}

export interface DrawerProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  readonly description?: string;
  readonly children?: ReactNode;
  readonly footer?: ReactNode;
  readonly side?: 'right' | 'left';
  readonly size?: 'sm' | 'md' | 'lg';
}

/** Side panel with the same keyboard contract as `Dialog`. */
export function Drawer({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  side = 'right',
  size = 'md',
}: DrawerProps) {
  const t = useTranslations('a11y');
  const { containerRef } = useModalBehaviour(open, onClose);
  const titleId = useId();
  const descriptionId = useId();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  if (!open || !mounted) return null;

  return createPortal(
    <div className={styles.portal}>
      <div className={styles.backdrop} aria-hidden="true" onMouseDown={onClose} />
      <div className={styles.layer}>
        <div
          ref={containerRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          aria-describedby={description ? descriptionId : undefined}
          tabIndex={-1}
          className={[
            styles.drawer,
            styles[`drawer-${side}`],
            styles[`drawerSize-${size}`],
            side === 'right' ? styles.drawerEnterRight : styles.drawerEnterLeft,
          ].join(' ')}
        >
          <header className={styles.header}>
            <div>
              <h2 className={styles.title} id={titleId}>
                {title}
              </h2>
              {description ? (
                <p className={styles.description} id={descriptionId}>
                  {description}
                </p>
              ) : null}
            </div>
            <IconButton label={t('dialog.close')} icon="x" onClick={onClose} />
          </header>
          <div className={styles.drawerBody}>{children}</div>
          {footer ? <footer className={styles.footer}>{footer}</footer> : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}

export interface ConfirmDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly description?: string;
  readonly confirmLabel: string;
  readonly cancelLabel?: string;
  readonly tone?: 'primary' | 'danger';
  readonly loading?: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/** Explicit confirmation for irreversible or legally significant actions. */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  tone = 'danger',
  loading = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const t = useTranslations('common');
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      description={description}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={loading}>
            {cancelLabel ?? t('actions.cancel')}
          </Button>
          <Button
            variant={tone === 'danger' ? 'danger' : 'primary'}
            onClick={onConfirm}
            loading={loading}
          >
            {confirmLabel}
          </Button>
        </>
      }
    />
  );
}

export interface TooltipProps {
  readonly content: ReactNode;
  readonly children: ReactElement<{ 'aria-describedby'?: string }>;
  readonly placement?: 'top' | 'bottom' | 'left' | 'right';
  readonly className?: string;
}

/**
 * Tooltip for supplementary information. Shows on hover *and* keyboard focus,
 * and is exposed through `aria-describedby` (never the only source of meaning).
 */
export function Tooltip({ content, children, placement = 'top', className }: TooltipProps) {
  const [visible, setVisible] = useState(false);
  const tooltipId = useId();

  const show = useCallback(() => setVisible(true), []);
  const hide = useCallback(() => setVisible(false), []);

  const child = cloneElement(children, {
    'aria-describedby': [children.props['aria-describedby'], visible ? tooltipId : undefined]
      .filter(Boolean)
      .join(' '),
  });

  return (
    <span
      className={[styles.tooltipWrapper, className].filter(Boolean).join(' ')}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      {child}
      {visible ? (
        <span
          role="tooltip"
          id={tooltipId}
          className={[styles.tooltip, styles[`tooltip-${placement}`]].join(' ')}
        >
          {content}
        </span>
      ) : null}
    </span>
  );
}

export interface InfoHintProps {
  readonly label: string;
  readonly children: ReactNode;
}

/** Small "i" affordance that opens a tooltip on hover/focus. */
export function InfoHint({ label, children }: InfoHintProps) {
  return (
    <Tooltip content={children}>
      <button type="button" className={styles.infoHint} aria-label={label}>
        <Icon name="info" size={15} />
      </button>
    </Tooltip>
  );
}
