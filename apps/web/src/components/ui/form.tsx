'use client';

import {
  useId,
  type ChangeEvent,
  type InputHTMLAttributes,
  type ReactNode,
  type Ref,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { Icon, type IconName } from '@/components/icons';
import { useTranslations } from '@/i18n/provider';
import styles from './form.module.css';

export interface FieldControlIds {
  readonly id: string;
  readonly 'aria-describedby': string | undefined;
  readonly 'aria-invalid': boolean | undefined;
  readonly 'aria-required': boolean | undefined;
}

export interface FieldProps {
  readonly label?: ReactNode;
  readonly hint?: ReactNode;
  /** Localized, user-facing message; also announced by screen readers. */
  readonly error?: string | undefined;
  readonly required?: boolean;
  readonly id?: string;
  readonly className?: string;
  readonly children: ReactNode | ((ids: FieldControlIds) => ReactNode);
}

function describedBy(ids: readonly (string | undefined)[]): string | undefined {
  const list = ids.filter((value): value is string => Boolean(value));
  return list.length > 0 ? list.join(' ') : undefined;
}

/**
 * Label + control + hint + error wrapper shared by every form field.
 *
 * Guarantees (docs/task.md §23.1): every control has a programmatic label, the
 * error message is linked through `aria-describedby`, `aria-invalid` is set and
 * the error is announced politely instead of only being coloured red.
 */
export function Field({ label, hint, error, required, id, className, children }: FieldProps) {
  const generatedId = useId();
  const baseId = id ?? generatedId;
  const hintId = hint ? `${baseId}-hint` : undefined;
  const errorId = error ? `${baseId}-error` : undefined;
  const ids: FieldControlIds = {
    id: baseId,
    'aria-describedby': describedBy([hintId, errorId]),
    'aria-invalid': error ? true : undefined,
    'aria-required': required || undefined,
  };

  return (
    <div
      className={[styles.field, error ? styles.fieldInvalid : '', className]
        .filter(Boolean)
        .join(' ')}
    >
      {label ? (
        <label className={styles.label} htmlFor={baseId} data-required={required || undefined}>
          {label}
        </label>
      ) : null}
      {typeof children === 'function' ? children(ids) : children}
      {hint ? (
        <p className={styles.hint} id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className={styles.error} id={errorId} role="alert">
          <Icon name="alert-circle" size={14} />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Input                                                                       */
/* -------------------------------------------------------------------------- */

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  readonly label?: ReactNode;
  readonly hint?: ReactNode;
  readonly error?: string | undefined;
  readonly leadingIcon?: IconName;
  readonly trailingSlot?: ReactNode;
  readonly inputSize?: 'sm' | 'md' | 'lg';
  readonly ref?: Ref<HTMLInputElement>;
}

export function Input({
  label,
  hint,
  error,
  leadingIcon,
  trailingSlot,
  inputSize = 'md',
  id,
  required,
  className,
  ref,
  ...rest
}: InputProps) {
  return (
    <Field
      label={label}
      hint={hint}
      error={error}
      required={required}
      id={id}
      className={className}
    >
      {(ids) => (
        <span className={[styles.control, styles[`control-${inputSize}`]].join(' ')}>
          {leadingIcon ? (
            <span className={styles.leadingIcon} aria-hidden="true">
              <Icon name={leadingIcon} size={16} />
            </span>
          ) : null}
          <input
            {...rest}
            {...ids}
            ref={ref}
            required={required}
            className={[
              styles.input,
              leadingIcon ? styles.inputWithLeading : '',
              error ? styles.inputInvalid : '',
            ]
              .filter(Boolean)
              .join(' ')}
          />
          {trailingSlot ? <span className={styles.trailingSlot}>{trailingSlot}</span> : null}
        </span>
      )}
    </Field>
  );
}

/* -------------------------------------------------------------------------- */
/* Textarea                                                                    */
/* -------------------------------------------------------------------------- */

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  readonly label?: ReactNode;
  readonly hint?: ReactNode;
  readonly error?: string | undefined;
  readonly ref?: Ref<HTMLTextAreaElement>;
}

export function Textarea({
  label,
  hint,
  error,
  id,
  required,
  className,
  rows = 5,
  ref,
  ...rest
}: TextareaProps) {
  return (
    <Field
      label={label}
      hint={hint}
      error={error}
      required={required}
      id={id}
      className={className}
    >
      {(ids) => (
        <textarea
          {...rest}
          {...ids}
          ref={ref}
          rows={rows}
          required={required}
          className={[styles.input, styles.textarea, error ? styles.inputInvalid : '']
            .filter(Boolean)
            .join(' ')}
        />
      )}
    </Field>
  );
}

/* -------------------------------------------------------------------------- */
/* Select                                                                      */
/* -------------------------------------------------------------------------- */

export interface SelectOption {
  readonly value: string;
  readonly label: string;
  readonly disabled?: boolean;
}

export interface SelectProps extends Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  'size' | 'children'
> {
  readonly label?: ReactNode;
  readonly hint?: ReactNode;
  readonly error?: string | undefined;
  readonly options?: readonly SelectOption[];
  readonly placeholder?: string;
  readonly selectSize?: 'sm' | 'md' | 'lg';
  readonly children?: ReactNode;
  readonly ref?: Ref<HTMLSelectElement>;
}

export function Select({
  label,
  hint,
  error,
  options,
  placeholder,
  selectSize = 'md',
  id,
  required,
  className,
  children,
  ref,
  ...rest
}: SelectProps) {
  return (
    <Field
      label={label}
      hint={hint}
      error={error}
      required={required}
      id={id}
      className={className}
    >
      {(ids) => (
        <span
          className={[styles.control, styles[`control-${selectSize}`], styles.selectWrap].join(' ')}
        >
          <select
            {...rest}
            {...ids}
            ref={ref}
            required={required}
            className={[styles.input, styles.select, error ? styles.inputInvalid : '']
              .filter(Boolean)
              .join(' ')}
          >
            {placeholder ? <option value="">{placeholder}</option> : null}
            {options
              ? options.map((option) => (
                  <option key={option.value} value={option.value} disabled={option.disabled}>
                    {option.label}
                  </option>
                ))
              : children}
          </select>
          <span className={styles.selectCaret} aria-hidden="true">
            <Icon name="chevron-down" size={16} />
          </span>
        </span>
      )}
    </Field>
  );
}

/* -------------------------------------------------------------------------- */
/* Checkbox                                                                    */
/* -------------------------------------------------------------------------- */

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  readonly label: ReactNode;
  readonly description?: ReactNode;
  readonly error?: string | undefined;
  readonly indeterminate?: boolean;
  readonly ref?: Ref<HTMLInputElement>;
}

export function Checkbox({
  label,
  description,
  error,
  indeterminate = false,
  id,
  className,
  ref,
  ...rest
}: CheckboxProps) {
  const generatedId = useId();
  const baseId = id ?? generatedId;
  const descriptionId = description ? `${baseId}-description` : undefined;
  const errorId = error ? `${baseId}-error` : undefined;
  return (
    <div className={[styles.choice, className].filter(Boolean).join(' ')}>
      <input
        {...rest}
        ref={(node) => {
          if (node) node.indeterminate = indeterminate;
          if (typeof ref === 'function') ref(node);
          else if (ref) ref.current = node;
        }}
        id={baseId}
        type="checkbox"
        aria-describedby={describedBy([descriptionId, errorId])}
        aria-invalid={error ? true : undefined}
        className={styles.checkboxInput}
      />
      <span className={styles.choiceBody}>
        <label className={styles.choiceLabel} htmlFor={baseId}>
          {label}
        </label>
        {description ? (
          <p className={styles.choiceDescription} id={descriptionId}>
            {description}
          </p>
        ) : null}
        {error ? (
          <p className={styles.error} id={errorId} role="alert">
            <Icon name="alert-circle" size={14} />
            <span>{error}</span>
          </p>
        ) : null}
      </span>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Radio                                                                       */
/* -------------------------------------------------------------------------- */

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  readonly label: ReactNode;
  readonly description?: ReactNode;
  readonly ref?: Ref<HTMLInputElement>;
}

export function Radio({ label, description, id, className, ref, ...rest }: RadioProps) {
  const generatedId = useId();
  const baseId = id ?? generatedId;
  const descriptionId = description ? `${baseId}-description` : undefined;
  return (
    <div className={[styles.choice, className].filter(Boolean).join(' ')}>
      <input
        {...rest}
        ref={ref}
        id={baseId}
        type="radio"
        aria-describedby={descriptionId}
        className={styles.checkboxInput}
      />
      <span className={styles.choiceBody}>
        <label className={styles.choiceLabel} htmlFor={baseId}>
          {label}
        </label>
        {description ? (
          <p className={styles.choiceDescription} id={descriptionId}>
            {description}
          </p>
        ) : null}
      </span>
    </div>
  );
}

export interface RadioGroupOption {
  readonly value: string;
  readonly label: string;
  readonly description?: string;
  readonly disabled?: boolean;
}

export interface RadioGroupProps {
  readonly legend: string;
  readonly name: string;
  readonly options: readonly RadioGroupOption[];
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly hint?: ReactNode;
  readonly error?: string;
  readonly orientation?: 'vertical' | 'horizontal';
  readonly className?: string;
}

export function RadioGroup({
  legend,
  name,
  options,
  value,
  onChange,
  hint,
  error,
  orientation = 'vertical',
  className,
}: RadioGroupProps) {
  const generatedId = useId();
  const hintId = hint ? `${generatedId}-hint` : undefined;
  const errorId = error ? `${generatedId}-error` : undefined;
  return (
    <fieldset
      className={[styles.fieldset, className].filter(Boolean).join(' ')}
      aria-describedby={describedBy([hintId, errorId])}
      aria-invalid={error ? true : undefined}
    >
      <legend className={styles.legend}>{legend}</legend>
      <div className={orientation === 'horizontal' ? styles.choiceRow : styles.choiceColumn}>
        {options.map((option) => (
          <Radio
            key={option.value}
            name={name}
            value={option.value}
            label={option.label}
            description={option.description}
            disabled={option.disabled}
            checked={value !== undefined ? value === option.value : undefined}
            onChange={onChange ? () => onChange(option.value) : undefined}
          />
        ))}
      </div>
      {hint ? (
        <p className={styles.hint} id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className={styles.error} id={errorId} role="alert">
          <Icon name="alert-circle" size={14} />
          <span>{error}</span>
        </p>
      ) : null}
    </fieldset>
  );
}

/* -------------------------------------------------------------------------- */
/* Switch                                                                      */
/* -------------------------------------------------------------------------- */

export interface SwitchProps {
  readonly label: ReactNode;
  readonly description?: ReactNode;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly disabled?: boolean;
  readonly id?: string;
  readonly className?: string;
}

/**
 * Switch built on a native button with `role="switch"`, so it is operable with
 * Space/Enter and announced with its checked state by screen readers.
 */
export function Switch({
  label,
  description,
  checked,
  onChange,
  disabled = false,
  id,
  className,
}: SwitchProps) {
  const generatedId = useId();
  const baseId = id ?? generatedId;
  const labelId = `${baseId}-label`;
  const descriptionId = description ? `${baseId}-description` : undefined;
  return (
    <div className={[styles.switch, className].filter(Boolean).join(' ')}>
      <button
        type="button"
        role="switch"
        id={baseId}
        aria-checked={checked}
        aria-labelledby={labelId}
        aria-describedby={descriptionId}
        disabled={disabled}
        className={[styles.switchTrack, checked ? styles.switchOn : ''].filter(Boolean).join(' ')}
        onClick={() => onChange(!checked)}
      >
        <span className={styles.switchThumb} aria-hidden="true" />
      </button>
      <span className={styles.switchBody}>
        <span className={styles.switchLabel} id={labelId}>
          {label}
        </span>
        {description ? (
          <span className={styles.choiceDescription} id={descriptionId}>
            {description}
          </span>
        ) : null}
      </span>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Search input                                                                */
/* -------------------------------------------------------------------------- */

export interface SearchInputProps extends Omit<InputProps, 'leadingIcon' | 'type'> {
  readonly label?: ReactNode;
}

/** Search field with icon and a built-in clear button. */
export function SearchInput({ label, value, onChange, ...rest }: SearchInputProps) {
  const t = useTranslations('common');
  const hasValue = typeof value === 'string' && value.length > 0;
  return (
    <Input
      {...rest}
      type="search"
      label={label}
      role="searchbox"
      leadingIcon="search"
      value={value}
      onChange={onChange}
      trailingSlot={
        hasValue ? (
          <button
            type="button"
            className={styles.clearButton}
            onClick={() => {
              const event = { target: { value: '' } } as ChangeEvent<HTMLInputElement>;
              onChange?.(event);
            }}
            aria-label={t('actions.clear')}
          >
            <Icon name="x" size={14} />
          </button>
        ) : null
      }
    />
  );
}
