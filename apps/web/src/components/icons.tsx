import type { SVGProps } from 'react';

/**
 * Dependency-free icon set.
 *
 * Icons are decorative by default (`aria-hidden`), because every icon in the
 * product is paired with a visible text label (docs/task.md §23.1: "понятные
 * иконки с текстовыми подсказками"). Pass `title` to expose a standalone icon
 * to assistive technology — mandatory for `IconButton`, which also takes a
 * `label`.
 */

export type IconName =
  | 'accessibility'
  | 'alert-circle'
  | 'alert-triangle'
  | 'archive'
  | 'arrow-left'
  | 'arrow-right'
  | 'arrow-up-down'
  | 'bar-chart'
  | 'bell'
  | 'briefcase'
  | 'building'
  | 'calendar'
  | 'check'
  | 'check-circle'
  | 'check-square'
  | 'chevron-down'
  | 'chevron-left'
  | 'chevron-right'
  | 'chevron-up'
  | 'clock'
  | 'copy'
  | 'credit-card'
  | 'download'
  | 'file-text'
  | 'filter'
  | 'folder'
  | 'gear'
  | 'globe'
  | 'grid'
  | 'home'
  | 'inbox'
  | 'info'
  | 'key'
  | 'layout'
  | 'list'
  | 'lock'
  | 'logout'
  | 'mail'
  | 'menu'
  | 'mic'
  | 'monitor'
  | 'moon'
  | 'more-horizontal'
  | 'paperclip'
  | 'pause'
  | 'pencil'
  | 'play'
  | 'plug'
  | 'plus'
  | 'printer'
  | 'receipt'
  | 'refresh'
  | 'search'
  | 'send'
  | 'shield'
  | 'shield-heart'
  | 'sparkles'
  | 'sun'
  | 'table'
  | 'trash'
  | 'upload'
  | 'user'
  | 'user-cog'
  | 'users'
  | 'x'
  | 'x-circle'
  | 'zap';

function circle(cx: number, cy: number, r: number): string {
  return `M ${cx - r} ${cy} a ${r} ${r} 0 1 0 ${r * 2} 0 a ${r} ${r} 0 1 0 ${-r * 2} 0`;
}

function rect(x: number, y: number, width: number, height: number, radius = 2): string {
  return `M ${x + radius} ${y} H ${x + width - radius} a ${radius} ${radius} 0 0 1 ${radius} ${radius} V ${y + height - radius} a ${radius} ${radius} 0 0 1 ${-radius} ${radius} H ${x + radius} a ${radius} ${radius} 0 0 1 ${-radius} ${-radius} V ${y + radius} a ${radius} ${radius} 0 0 1 ${radius} ${-radius} Z`;
}

const ICON_PATHS: Record<IconName, readonly string[]> = {
  accessibility: [circle(12, 5, 2), 'M3 9h18', 'M12 9v6', 'M8 21l4-6 4 6'],
  'alert-circle': [circle(12, 12, 9), 'M12 7.5v5.5', 'M12 16.5h.01'],
  'alert-triangle': ['M12 3.5 21.5 20H2.5Z', 'M12 9.5v4.5', 'M12 17h.01'],
  archive: [rect(3, 4, 18, 4, 1), 'M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8', 'M10 12h4'],
  'arrow-left': ['M20 12H4', 'M10 6l-6 6 6 6'],
  'arrow-right': ['M4 12h16', 'M14 6l6 6-6 6'],
  'arrow-up-down': ['M8 4v16', 'M4 8l4-4 4 4', 'M16 20V4', 'M12 16l4 4 4-4'],
  'bar-chart': ['M4 20V11', 'M10 20V4', 'M16 20v-6', 'M22 20H2'],
  bell: ['M6 9a6 6 0 0 1 12 0c0 6 2.5 7.5 2.5 7.5H3.5S6 15 6 9Z', 'M10.2 20.5a2 2 0 0 0 3.6 0'],
  briefcase: [
    rect(3, 7, 18, 13, 2),
    'M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7',
    'M3 12h18',
  ],
  building: [
    rect(4, 3, 16, 18, 1),
    'M9 7h2',
    'M13 7h2',
    'M9 11h2',
    'M13 11h2',
    'M9 15h2',
    'M13 15h2',
    'M3 21h18',
  ],
  calendar: [rect(3, 5, 18, 16, 2), 'M8 3v4', 'M16 3v4', 'M3 10h18'],
  check: ['M4.5 12.5 9.5 17.5 19.5 6.5'],
  'check-circle': [circle(12, 12, 9), 'M8 12.5l2.8 2.8L16.5 9.5'],
  'check-square': [rect(3, 3, 18, 18, 3), 'M8 12.5l2.8 2.8L16.5 9'],
  'chevron-down': ['M6 9.5 12 15.5 18 9.5'],
  'chevron-left': ['M15 5.5 9 12l6 6.5'],
  'chevron-right': ['M9 5.5 15 12l-6 6.5'],
  'chevron-up': ['M6 14.5 12 8.5l6 6'],
  clock: [circle(12, 12, 9), 'M12 7v5.5l4 2'],
  copy: [rect(9, 9, 12, 12, 2), 'M6 15H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1'],
  'credit-card': [rect(2, 5, 20, 14, 2), 'M2 10h20', 'M6 15h4'],
  download: ['M12 3v12', 'M7 10l5 5 5-5', 'M4 20h16'],
  'file-text': [
    'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z',
    'M14 3v5h5',
    'M9 13h6',
    'M9 17h4',
  ],
  filter: ['M3 5h18l-7 8v7l-4-2v-5Z'],
  folder: ['M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z'],
  gear: [
    circle(12, 12, 3.2),
    'M12 3v2.2',
    'M12 18.8V21',
    'M3 12h2.2',
    'M18.8 12H21',
    'M5.6 5.6l1.6 1.6',
    'M16.8 16.8l1.6 1.6',
    'M18.4 5.6l-1.6 1.6',
    'M7.2 16.8l-1.6 1.6',
  ],
  globe: [circle(12, 12, 9), 'M3 12h18', 'M12 3c3 3.5 3 14.5 0 18-3-3.5-3-14.5 0-18Z'],
  grid: [rect(3, 3, 8, 8, 2), rect(13, 3, 8, 8, 2), rect(3, 13, 8, 8, 2), rect(13, 13, 8, 8, 2)],
  home: ['M3.5 11 12 3.5 20.5 11', 'M5.5 9.8V20h13V9.8', 'M10 20v-5h4v5'],
  inbox: ['M3 12.5h5l1.5 3h5l1.5-3h5', 'M5.5 4.5h13l2.5 8V19a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6.5Z'],
  info: [circle(12, 12, 9), 'M12 11v5.5', 'M12 7.5h.01'],
  key: [circle(7.5, 15.5, 3.5), 'M10.2 13 20 3.2', 'M16.5 3.5 20.5 7.5', 'M14 6l3 3'],
  layout: [rect(3, 3, 18, 18, 2), 'M3 9h18', 'M9 21V9'],
  list: ['M8 6h13', 'M8 12h13', 'M8 18h13', 'M3.5 6h.01', 'M3.5 12h.01', 'M3.5 18h.01'],
  lock: [rect(4, 10, 16, 10, 2), 'M8 10V7a4 4 0 0 1 8 0v3', 'M12 14v2.5'],
  logout: ['M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3', 'M10 16.5 14.5 12 10 7.5', 'M14.5 12H3'],
  mail: [rect(3, 5, 18, 14, 2), 'M3.5 7 12 13l8.5-6'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  mic: [rect(9, 3, 6, 11, 3), 'M5.5 11a6.5 6.5 0 0 0 13 0', 'M12 17.5V21', 'M9 21h6'],
  monitor: [rect(2, 4, 20, 12, 2), 'M8 20h8', 'M12 16v4'],
  moon: ['M20.5 14.2A8.6 8.6 0 1 1 9.8 3.5a7 7 0 0 0 10.7 10.7Z'],
  'more-horizontal': [circle(5.5, 12, 1.4), circle(12, 12, 1.4), circle(18.5, 12, 1.4)],
  paperclip: [
    'M20 11.5 11.6 20a5 5 0 0 1-7-7l8.3-8.4a3.4 3.4 0 0 1 4.9 4.8l-8.2 8.3a1.8 1.8 0 0 1-2.5-2.5L15 7.5',
  ],
  pause: ['M9 5v14', 'M15 5v14'],
  pencil: [
    'M4 20v-3.5L15.8 4.7a1.8 1.8 0 0 1 2.5 0l1 1a1.8 1.8 0 0 1 0 2.5L7.5 20Z',
    'M14.5 6 18 9.5',
  ],
  play: ['M7 4.5 19 12 7 19.5Z'],
  plug: ['M9 3v5', 'M15 3v5', 'M6 8h12v4a6 6 0 0 1-12 0Z', 'M12 18v3'],
  plus: ['M12 5v14', 'M5 12h14'],
  printer: ['M6 9V3.5h12V9', rect(3, 9, 18, 8, 2), 'M7 17h10v3.5H7Z'],
  receipt: ['M6 3h12v18l-3-2-3 2-3-2-3 2Z', 'M9 8h6', 'M9 12h6', 'M9 16h3'],
  refresh: ['M20.5 12a8.5 8.5 0 1 1-2.6-6.1', 'M20.5 4v5h-5'],
  search: [circle(11, 11, 7), 'M16.2 16.2 21 21'],
  send: ['M21.5 2.5 11 13', 'M21.5 2.5 15 21.5l-4-8.5-8.5-4Z'],
  shield: ['M12 3 20 6v6c0 4.7-3.3 7.7-8 9-4.7-1.3-8-4.3-8-9V6Z', 'M9 12l2.2 2.2L15.5 10'],
  'shield-heart': [
    'M12 3 20 6v6c0 4.7-3.3 7.7-8 9-4.7-1.3-8-4.3-8-9V6Z',
    'M12 16s-3-1.9-3-4a1.8 1.8 0 0 1 3-1.2A1.8 1.8 0 0 1 15 12c0 2.1-3 4-3 4Z',
  ],
  sparkles: [
    'M12 3.5 13.6 8 18 9.6 13.6 11.2 12 15.7 10.4 11.2 6 9.6 10.4 8Z',
    'M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8Z',
  ],
  sun: [
    circle(12, 12, 4),
    'M12 2.5V5',
    'M12 19v2.5',
    'M2.5 12H5',
    'M19 12h2.5',
    'M5.2 5.2 7 7',
    'M17 17l1.8 1.8',
    'M18.8 5.2 17 7',
    'M7 17l-1.8 1.8',
  ],
  table: [rect(3, 3, 18, 18, 2), 'M3 9h18', 'M3 15h18', 'M12 3v18'],
  trash: ['M4 7h16', 'M9 7V4.5h6V7', 'M6 7l1 13.5h10L18 7', 'M10 11v6', 'M14 11v6'],
  upload: ['M12 16V4', 'M7 9l5-5 5 5', 'M4 20h16'],
  user: [circle(12, 8, 3.8), 'M4.5 20.5c0-4.1 3.4-7 7.5-7s7.5 2.9 7.5 7'],
  'user-cog': [
    circle(9.5, 8, 3.5),
    'M2.5 20.5c0-3.9 3.1-6.6 7-6.6',
    circle(17.5, 16.5, 3),
    'M17.5 12.5v1.5',
    'M17.5 19v1.5',
    'M14 16.5h1.5',
    'M19.5 16.5H21',
  ],
  users: [
    circle(9, 8, 3.5),
    'M2.5 20c0-3.6 2.9-6.2 6.5-6.2s6.5 2.6 6.5 6.2',
    'M16 4.6a3.5 3.5 0 0 1 0 6.8',
    'M18 14.4c2.1.7 3.5 2.6 3.5 5.1',
  ],
  x: ['M6 6l12 12', 'M18 6 6 18'],
  'x-circle': [circle(12, 12, 9), 'M9 9l6 6', 'M15 9l-6 6'],
  zap: ['M13 2.5 4.5 14H11l-1 7.5L18.5 10H12Z'],
};

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  readonly name: IconName;
  readonly size?: number;
  /** Accessible name. Omit for decorative icons next to a text label. */
  readonly title?: string;
}

export function Icon({ name, size = 18, title, ...rest }: IconProps) {
  const paths = ICON_PATHS[name];
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      focusable="false"
      {...rest}
    >
      {title ? <title>{title}</title> : null}
      {paths.map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}

export const iconNames = Object.keys(ICON_PATHS) as IconName[];
