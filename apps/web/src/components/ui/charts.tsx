'use client';

import { useId, useState, type MouseEvent } from 'react';
import styles from './charts.module.css';

/**
 * Lightweight SVG charts in the firmspace style — gradient cyan → indigo →
 * violet, a soft glow and hover tooltips. No chart library: the data sets here
 * are small and the rendering is a handful of SVG shapes.
 */

export interface ChartPoint {
  readonly label: string;
  readonly value: number;
}

interface ChartProps {
  readonly ariaLabel: string;
  readonly formatValue?: (value: number) => string;
}

function scale(max: number): number {
  return max > 0 ? max : 1;
}

/**
 * A tiny line chart without axes, for cards: one gradient stroke, a soft area
 * underneath, drawn in when it appears.
 */
export function Sparkline({
  values,
  ariaLabel,
  className,
}: {
  readonly values: readonly number[];
  readonly ariaLabel: string;
  readonly className?: string;
}) {
  const id = useId().replace(/[:]/g, '');
  const width = 96;
  const height = 32;
  const max = scale(Math.max(...(values.length > 0 ? values : [0])));
  const step = values.length > 1 ? width / (values.length - 1) : width;
  const points: readonly (readonly [number, number])[] = values.map(
    (value, index) => [index * step, height - 3 - (value / max) * (height - 8)] as const,
  );
  const line = points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `M0,${height} L${points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' L')} L${width},${height} Z`;

  return (
    <svg
      className={[styles.sparkline, className].filter(Boolean).join(' ')}
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      role="img"
      aria-label={ariaLabel}
      focusable="false"
    >
      <defs>
        <linearGradient id={`spark-stroke-${id}`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="var(--color-cyan)" />
          <stop offset="0.55" stopColor="var(--color-accent)" />
          <stop offset="1" stopColor="var(--color-violet)" />
        </linearGradient>
        <linearGradient id={`spark-area-${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--color-accent)" stopOpacity="0.28" />
          <stop offset="1" stopColor="var(--color-accent)" stopOpacity="0" />
        </linearGradient>
      </defs>
      {values.length > 1 ? (
        <>
          <path d={area} fill={`url(#spark-area-${id})`} className={styles.areaDraw} />
          <polyline
            points={line}
            fill="none"
            stroke={`url(#spark-stroke-${id})`}
            strokeWidth={1.6}
            strokeLinecap="round"
            strokeLinejoin="round"
            className={styles.lineDraw}
          />
        </>
      ) : (
        <line
          x1="0"
          x2={width}
          y1={height / 2}
          y2={height / 2}
          stroke="var(--color-border-strong)"
          strokeDasharray="3 4"
        />
      )}
    </svg>
  );
}

/**
 * Bars with a vertical cyan → indigo → violet gradient, a glow, hover focus and
 * a tooltip. `horizontal` lays the label and the value beside the bar instead
 * of under it (used for the reports list); its tooltip is pure CSS.
 */
export function BarChart({
  points,
  ariaLabel,
  horizontal = false,
  height = 180,
  formatValue = (value) => String(value),
  showValue = false,
}: ChartProps & {
  readonly points: readonly ChartPoint[];
  readonly horizontal?: boolean;
  readonly height?: number;
  readonly showValue?: boolean;
}) {
  const id = useId().replace(/[:]/g, '');
  const [hover, setHover] = useState<number | null>(null);
  const max = scale(Math.max(...points.map((point) => point.value)));
  const barCount = points.length;

  if (horizontal) {
    return (
      <ul className={styles.horizontalList}>
        {points.map((point, index) => {
          const widthPercent = Math.max(2, Math.round((point.value / max) * 100));
          return (
            <li key={`${point.label}-${index}`} className={styles.horizontalRow}>
              <span className={styles.horizontalLabel}>{point.label}</span>
              <span className={styles.horizontalTrack} aria-hidden="true">
                <span className={styles.horizontalBar} style={{ width: `${widthPercent}%` }} />
              </span>
              <span className={styles.horizontalValue}>{formatValue(point.value)}</span>
              <span className={styles.tooltip}>
                {point.label}: {formatValue(point.value)}
              </span>
            </li>
          );
        })}
      </ul>
    );
  }

  const onMove = (event: MouseEvent<HTMLDivElement>): void => {
    const rect = event.currentTarget.getBoundingClientRect();
    const index = Math.min(
      barCount - 1,
      Math.max(0, Math.floor(((event.clientX - rect.left) / rect.width) * barCount)),
    );
    setHover(index);
  };

  return (
    <div
      className={styles.verticalWrap}
      onMouseMove={onMove}
      onMouseLeave={() => setHover(null)}
    >
      <svg
        className={styles.svg}
        viewBox={`0 0 320 ${height}`}
        height={height}
        role="img"
        aria-label={ariaLabel}
        focusable="false"
      >
        <defs>
          <linearGradient id={`bars-${id}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--color-cyan)" />
            <stop offset="0.5" stopColor="var(--color-accent)" />
            <stop offset="1" stopColor="var(--color-violet)" />
          </linearGradient>
        </defs>
        {points.map((point, index) => {
          const slot = 320 / barCount;
          const barWidth = Math.min(28, slot * 0.62);
          const barHeight = Math.max(2, (point.value / max) * (height - 26));
          const x = slot * index + (slot - barWidth) / 2;
          const y = height - 22 - barHeight;
          const active = hover === index;
          return (
            <g key={`${point.label}-${index}`}>
              <rect
                x={x}
                y={y}
                width={barWidth}
                height={barHeight}
                rx={5}
                fill={`url(#bars-${id})`}
                className={active ? styles.barActive : styles.bar}
              >
                <title>{`${point.label}: ${formatValue(point.value)}`}</title>
              </rect>
              {showValue && point.value > 0 ? (
                <text x={x + barWidth / 2} y={y - 5} textAnchor="middle" className={styles.barValue}>
                  {formatValue(point.value)}
                </text>
              ) : null}
            </g>
          );
        })}
        <line x1="0" x2="320" y1={height - 22} y2={height - 22} className={styles.baseline} />
      </svg>
      <div className={styles.verticalLabels} aria-hidden="true">
        {points.map((point, index) => (
          <span key={`${point.label}-${index}`} className={styles.verticalLabel}>
            {point.label}
          </span>
        ))}
      </div>
      {hover !== null && points[hover] ? (
        <div
          className={styles.tooltip}
          style={{ left: `${Math.min(Math.max(4, ((hover + 0.5) / barCount) * 100), 62)}%`, top: 0 }}
        >
          {points[hover].label}: {formatValue(points[hover].value)}
        </div>
      ) : null}
    </div>
  );
}

/**
 * A line over time: gradient stroke, soft area, a dot on hover and an animated
 * draw-in. The x labels are HTML under the canvas so they stay crisp.
 */
export function TrendLine({
  points,
  ariaLabel,
  height = 160,
  formatValue = (value) => String(value),
}: ChartProps & {
  readonly points: readonly ChartPoint[];
  readonly height?: number;
}) {
  const id = useId().replace(/[:]/g, '');
  const [hover, setHover] = useState<number | null>(null);
  const max = scale(Math.max(...points.map((point) => point.value)));
  const padding = 6;
  const inner = height - 26;
  const step = points.length > 1 ? 320 / (points.length - 1) : 320;
  const coords = points.map((point, index) => {
    const x = points.length > 1 ? index * step : 160;
    const y = padding + inner - (point.value / max) * (inner - padding * 2);
    return [x, y] as const;
  });
  const line = coords.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `M0,${height - 22} L${coords.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' L')} L320,${height - 22} Z`;

  const onMove = (event: MouseEvent<HTMLDivElement>): void => {
    const rect = event.currentTarget.getBoundingClientRect();
    const ratio = (event.clientX - rect.left) / rect.width;
    const index = Math.min(
      points.length - 1,
      Math.max(0, Math.round(ratio * Math.max(0, points.length - 1))),
    );
    setHover(index);
  };

  return (
    <div
      className={styles.verticalWrap}
      onMouseMove={onMove}
      onMouseLeave={() => setHover(null)}
    >
      <svg
        className={styles.svg}
        viewBox={`0 0 320 ${height}`}
        height={height}
        role="img"
        aria-label={ariaLabel}
        focusable="false"
      >
        <defs>
          <linearGradient id={`trend-stroke-${id}`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="var(--color-cyan)" />
            <stop offset="0.55" stopColor="var(--color-accent)" />
            <stop offset="1" stopColor="var(--color-violet)" />
          </linearGradient>
          <linearGradient id={`trend-area-${id}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--color-accent)" stopOpacity="0.26" />
            <stop offset="1" stopColor="var(--color-accent)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={area} fill={`url(#trend-area-${id})`} className={styles.areaDraw} />
        <polyline
          points={line}
          fill="none"
          stroke={`url(#trend-stroke-${id})`}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          className={styles.lineDraw}
        />
        {hover !== null && coords[hover] ? (
          <>
            <line
              x1={coords[hover][0]}
              x2={coords[hover][0]}
              y1={4}
              y2={height - 22}
              className={styles.guide}
            />
            <circle cx={coords[hover][0]} cy={coords[hover][1]} r={4} className={styles.dot} />
          </>
        ) : null}
      </svg>
      <div className={styles.verticalLabels} aria-hidden="true">
        {points.map((point, index) => (
          <span key={`${point.label}-${index}`} className={styles.verticalLabel}>
            {point.label}
          </span>
        ))}
      </div>
      {hover !== null && points[hover] ? (
        <div
          className={styles.tooltip}
          style={{
            left: `${Math.min(
              Math.max(4, (hover / Math.max(1, points.length - 1)) * 100),
              70,
            )}%`,
            top: 0,
          }}
        >
          {points[hover].label}: {formatValue(points[hover].value)}
        </div>
      ) : null}
    </div>
  );
}

/** A decorative moving dashed line (the "moving lines" of the site). */
export function FlowLine({
  vertical = false,
  className,
}: {
  readonly vertical?: boolean;
  readonly className?: string;
}) {
  return (
    <div
      aria-hidden="true"
      className={[vertical ? 'flowLineVertical' : 'flowLine', className].filter(Boolean).join(' ')}
    />
  );
}
