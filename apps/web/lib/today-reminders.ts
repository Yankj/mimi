/**
 * In-page 「今日提醒」.
 *
 * iOS plans these in ParentingReminderPlanner: confirmed arrangements only,
 * at most 2 per local day, and a fire time inside 21:00–08:00 moves to 20:30
 * the evening before that quiet window. Delivery on iOS is the system
 * notification center. This page only lists the same plan.
 *
 * Family sync is not on. The page source stays unsynced, the block is not
 * rendered, and nothing is written to a browser store or sent over the network.
 */

import { copy } from "./copy";

export type ReminderLead = "minutes15" | "minutes30" | "hour1" | "eveningBefore";

export type ConfirmedArrangement = {
  id: string;
  title: string;
  dueAt: string;
};

export type TodayReminderSource =
  | { kind: "unsynced" }
  | { kind: "synced"; arrangements: readonly ConfirmedArrangement[]; lead?: ReminderLead };

export type TodayReminderRow = {
  id: string;
  title: string;
  detail: string;
};

const QUIET_START = 21;
const QUIET_END = 8;
const MAX_PER_DAY = 2;
const DEFAULT_LEAD: ReminderLead = "hour1";

/** The only source the page may use until a shared read exists. */
export function sharedTodayReminderSource(): TodayReminderSource {
  return { kind: "unsynced" };
}

type Wall = { year: number; month: number; day: number; hour: number; minute: number; second: number };

function wall(instant: Date, timeZone: string): Wall {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    }).formatToParts(instant).map((part) => [part.type, part.value]),
  );
  let hour = Number(parts.hour);
  if (hour === 24) hour = 0;
  return {
    year: Number(parts.year),
    month: Number(parts.month),
    day: Number(parts.day),
    hour,
    minute: Number(parts.minute),
    second: Number(parts.second),
  };
}

function dayKeyFromWall(value: Wall): string {
  const month = String(value.month).padStart(2, "0");
  const day = String(value.day).padStart(2, "0");
  return `${value.year}-${month}-${day}`;
}

export function localDayKey(instant: Date, timeZone: string): string {
  return dayKeyFromWall(wall(instant, timeZone));
}

function offsetMs(instant: Date, timeZone: string): number {
  const value = wall(instant, timeZone);
  return Date.UTC(value.year, value.month - 1, value.day, value.hour, value.minute, value.second) - instant.getTime();
}

function zonedTime(year: number, month: number, day: number, hour: number, minute: number, timeZone: string): Date {
  const utc = Date.UTC(year, month - 1, day, hour, minute, 0);
  const first = utc - offsetMs(new Date(utc), timeZone);
  return new Date(utc - offsetMs(new Date(first), timeZone));
}

function isQuiet(instant: Date, timeZone: string): boolean {
  const hour = wall(instant, timeZone).hour;
  return hour >= QUIET_START || hour < QUIET_END;
}

function previousDay(year: number, month: number, day: number, timeZone: string): Wall {
  const noon = zonedTime(year, month, day, 12, 0, timeZone);
  return wall(new Date(noon.getTime() - 24 * 60 * 60 * 1000), timeZone);
}

function leadMinutes(lead: ReminderLead): number | null {
  if (lead === "minutes15") return 15;
  if (lead === "minutes30") return 30;
  if (lead === "hour1") return 60;
  return null;
}

function clock(instant: Date, timeZone: string): string {
  const value = wall(instant, timeZone);
  return `${String(value.hour).padStart(2, "0")}:${String(value.minute).padStart(2, "0")}`;
}

/** Same quiet-hours move as ParentingReminderPlanner.fireDate. */
export function reminderFire(due: Date, lead: ReminderLead, timeZone: string): { at: Date; raw: Date; moved: boolean } | null {
  let raw: Date;
  const minutes = leadMinutes(lead);
  if (minutes != null) {
    raw = new Date(due.getTime() - minutes * 60 * 1000);
  } else {
    const dueWall = wall(due, timeZone);
    const previous = previousDay(dueWall.year, dueWall.month, dueWall.day, timeZone);
    raw = zonedTime(previous.year, previous.month, previous.day, 20, 0, timeZone);
  }
  if (!isQuiet(raw, timeZone)) return { at: raw, raw, moved: false };
  const rawWall = wall(raw, timeZone);
  const evening = rawWall.hour < QUIET_END
    ? previousDay(rawWall.year, rawWall.month, rawWall.day, timeZone)
    : rawWall;
  return {
    at: zonedTime(evening.year, evening.month, evening.day, 20, 30, timeZone),
    raw,
    moved: true,
  };
}

function detail(fire: { at: Date; raw: Date; moved: boolean }, reminded: boolean, timeZone: string): string {
  if (fire.moved) {
    const when = clock(fire.raw, timeZone);
    return reminded
      ? `本应 ${when}，已挤到今晚不发，改在前一晚 20:30 提醒过`
      : `本应 ${when}，已挤到今晚不发，改在前一晚 20:30 提醒`;
  }
  return `${clock(fire.at, timeZone)} 前提醒 · ${copy.todayRemindersDailyCap}`;
}

export function todayReminderView(source: TodayReminderSource, now: Date, timeZone: string) {
  if (source.kind !== "synced") return { visible: false as const };
  const lead = source.lead ?? DEFAULT_LEAD;
  const today = localDayKey(now, timeZone);
  const planned = source.arrangements
    .filter((item) => item.title.trim() !== "" && Number.isFinite(Date.parse(item.dueAt)) && new Date(item.dueAt).getTime() > now.getTime())
    .flatMap((item) => {
      const due = new Date(item.dueAt);
      const fire = reminderFire(due, lead, timeZone);
      return fire ? [{ item, fire }] : [];
    })
    .sort((a, b) => a.fire.at.getTime() - b.fire.at.getTime() || a.item.id.localeCompare(b.item.id));

  const used = new Map<string, number>();
  const rows: TodayReminderRow[] = [];
  for (const entry of planned) {
    const slot = localDayKey(entry.fire.at, timeZone);
    const count = used.get(slot) ?? 0;
    if (count >= MAX_PER_DAY) continue;
    used.set(slot, count + 1);
    const onToday = slot === today || (entry.fire.moved && localDayKey(entry.fire.raw, timeZone) === today);
    if (!onToday) continue;
    rows.push({
      id: entry.item.id,
      title: entry.item.title.trim(),
      detail: detail(entry.fire, entry.fire.at.getTime() <= now.getTime() + 5000, timeZone),
    });
  }

  return {
    visible: true as const,
    rows: rows.slice(0, MAX_PER_DAY),
    empty: rows.length === 0 ? copy.todayRemindersEmpty : null,
  };
}
