const timeFormats = new Map<string, Intl.DateTimeFormat>();

function timeFormat(timeZone: string): Intl.DateTimeFormat {
  let format = timeFormats.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    timeFormats.set(timeZone, format);
  }
  return format;
}

export function formatTime(instant: string, timeZone: string): string {
  return timeFormat(timeZone).format(new Date(instant));
}

export function formatTimeRange(start: string, end: string, timeZone: string): string {
  return `${formatTime(start, timeZone)}–${formatTime(end, timeZone)}`;
}

export function todayIn(timeZone: string, now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)!.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function addDays(date: string, days: number): string {
  const utc = new Date(`${date}T00:00:00Z`);
  utc.setUTCDate(utc.getUTCDate() + days);
  return utc.toISOString().slice(0, 10);
}

export function formatDate(date: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
    .format(new Date(`${date}T00:00:00Z`))
    .replace(',', '');
}

export function localDateOf(instant: string, timeZone: string): string {
  return todayIn(timeZone, new Date(instant));
}

export function minutesSince(instant: string, localTime: string, timeZone: string): number {
  const [hours, minutes] = formatTime(instant, timeZone).split(':').map(Number);
  const [fromHours, fromMinutes] = localTime.split(':').map(Number);
  return hours * 60 + minutes - (fromHours * 60 + fromMinutes);
}
