import { describe, expect, it } from 'vitest';
import { generateEstate } from '../src/api/seed.ts';

/*
 * The demo's answers, pinned. If the generator changes and a story moves, these fail before the stage does —
 * update the numbers in README "Demo script" together with them.
 */

const anchor = new Date('2026-10-12T00:00:00Z');
const estate = generateEstate(443929, anchor);
const farmName = new Map(estate.farms.map((f) => [f.id, f.name]));
const country = new Map(estate.farms.map((f) => [f.id, f.country]));

describe('generateEstate', () => {
  it('is deterministic', () => {
    const again = generateEstate(443929, anchor);
    expect(again.devices.length).toBe(estate.devices.length);
    expect(again.issues.map((i) => i.created_at.getTime())).toEqual(estate.issues.map((i) => i.created_at.getTime()));
  });

  it('keeps every timestamp before the anchor', () => {
    expect(estate.issues.every((i) => i.created_at < anchor && (!i.resolved_at || i.resolved_at <= anchor))).toBe(true);
    expect(estate.devices.every((d) => d.last_seen_at < anchor)).toBe(true);
  });

  it('story 1: Hoeve De Linde has the most offline devices, all on 5.16.0', () => {
    const offline = new Map<string, number>();
    for (const d of estate.devices) if (d.status === 'OFFLINE') offline.set(d.farm_id, (offline.get(d.farm_id) ?? 0) + 1);
    const [top] = [...offline].sort((a, b) => b[1] - a[1]);
    expect(farmName.get(top[0])).toBe('Hoeve De Linde');
    expect(top[1]).toBe(44);

    const rate = (firmware: string) => {
      const on = estate.devices.filter((d) => d.firmware_version === firmware);
      return on.filter((d) => d.status === 'OFFLINE').length / on.length;
    };
    expect(rate('5.16.0')).toBeGreaterThan(0.35);
    expect(rate('5.15.0')).toBeLessThan(0.06);
  });

  it('story 2: German gateway issues quadruple week on week', () => {
    const week = (from: number, to: number) =>
      estate.issues.filter((i) => {
        const daysAgo = (anchor.getTime() - i.created_at.getTime()) / 86_400_000;
        return i.definition_id === 'def-gateway' && country.get(i.farm_id) === 'Germany' && daysAgo > from && daysAgo <= to;
      }).length;
    expect(week(0, 7)).toBe(91);
    expect(week(7, 14)).toBe(19);
  });

  it('story 3: low-battery devices are an Irish cluster', () => {
    const low = estate.devices.filter((d) => d.status === 'LOW_BATTERY');
    expect(low.length).toBeGreaterThan(300);
    expect(low.every((d) => country.get(d.farm_id) === 'Ireland')).toBe(true);
  });
});
