import { describe, expect, test } from 'vitest';

import { matchesQuery, normalize } from './normalize';

describe('accent-insensitive matching', () => {
  test('removes case, accents and the Vietnamese đ', () => {
    expect(normalize('Lê Minh Đức')).toBe('le minh duc');
    expect(normalize('ĐỨC')).toBe('duc');
    expect(normalize('  Kiểm   tra phanh ')).toBe('kiem tra phanh');
  });

  test('treats decomposed (NFD) input like composed input', () => {
    expect(normalize('Đức'.normalize('NFD'))).toBe('duc');
  });

  test('matches a query anywhere in any of the given texts', () => {
    expect(matchesQuery('kiem tra', 'Kiểm tra phanh', 'BRAKE_CHECK')).toBe(true);
    expect(matchesQuery('brake', 'Kiểm tra phanh', 'BRAKE_CHECK')).toBe(true);
    expect(matchesQuery('oil', 'Kiểm tra phanh', 'BRAKE_CHECK')).toBe(false);
    expect(matchesQuery('', 'anything')).toBe(true);
  });
});
