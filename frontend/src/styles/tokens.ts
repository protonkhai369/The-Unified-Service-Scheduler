export const palette = {
  navy: '#0B2545',
  ocean: '#0B5FAE',
  yellow: '#FACC15',
  ink: '#0A0A0A',
  white: '#FFFFFF',
  mist: '#F5F7FA',
  slate: '#475569',
  field: '#64748B',
  line: '#E2E8F0',
  danger: '#B91C1C',
} as const;

export type Token = keyof typeof palette;

type Pair = readonly [foreground: Token, background: Token, use: string];

export const textPairs: readonly Pair[] = [
  ['ink', 'white', 'body text'],
  ['navy', 'white', 'headings'],
  ['ocean', 'white', 'links and secondary buttons'],
  ['slate', 'white', 'secondary text'],
  ['danger', 'white', 'error text and the cancel action'],
  ['ink', 'mist', 'text on subtle surfaces'],
  ['slate', 'mist', 'secondary text on subtle surfaces'],
  ['ocean', 'mist', 'links on subtle surfaces'],
  ['white', 'navy', 'top bar text'],
  ['yellow', 'navy', 'active navigation'],
  ['ink', 'yellow', 'primary button and selected slot'],
];

export const uiPairs: readonly Pair[] = [
  ['field', 'white', 'input and slot borders'],
  ['field', 'mist', 'borders on subtle surfaces'],
  ['ocean', 'white', 'focus ring'],
  ['yellow', 'navy', 'focus ring on the top bar'],
  ['ink', 'yellow', 'selected slot border'],
];

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16) / 255);
  const [r, g, b] = channels.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrastRatio(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}
