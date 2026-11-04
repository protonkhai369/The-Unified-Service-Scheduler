export function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

export function matchesQuery(query: string, ...texts: string[]): boolean {
  const wanted = normalize(query);
  return wanted === '' || texts.some((text) => normalize(text).includes(wanted));
}
