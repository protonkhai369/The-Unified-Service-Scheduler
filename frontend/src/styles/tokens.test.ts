import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, test } from 'vitest';

import { contrastRatio, palette, textPairs, uiPairs } from './tokens';

describe('design tokens', () => {
  test.each(textPairs)('text %s on %s meets 4.5:1 (%s)', (foreground, background) => {
    expect(contrastRatio(palette[foreground], palette[background])).toBeGreaterThanOrEqual(4.5);
  });

  test.each(uiPairs)('UI %s on %s meets 3:1 (%s)', (foreground, background) => {
    expect(contrastRatio(palette[foreground], palette[background])).toBeGreaterThanOrEqual(3);
  });

  test('yellow is never used on white', () => {
    const pairs = [...textPairs, ...uiPairs];

    expect(pairs.filter(([foreground, background]) => foreground === 'yellow' && background === 'white')).toEqual([]);
    expect(contrastRatio(palette.yellow, palette.white)).toBeLessThan(3);
  });

  test('the stylesheet declares every token with the same value', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/index.css'), 'utf-8');

    for (const [name, value] of Object.entries(palette)) {
      expect(css, name).toMatch(new RegExp(`--color-${name}:\\s*${value};`, 'i'));
    }
  });

  test('contrast ratio matches WCAG reference values', () => {
    expect(contrastRatio('#000000', '#FFFFFF')).toBeCloseTo(21, 1);
    expect(contrastRatio('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
  });
});
