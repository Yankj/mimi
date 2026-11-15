/**
 * In-page 「今日提醒」.
 *
 * iOS plans these from confirmed arrangements in the on-device encrypted ledger
 * (`ParentingReminderPlanner` / `ParentingWorkspaceStore`) and then delivers
 * them only through the system notification center. The iOS app does not call
 * the family HTTP route, and the family reminder table is not that ledger.
 *
 * This module never reads or writes a browser store, and it never calls the
 * network. The page source stays `unsynced` until a shared read the iOS app
 * actually uses exists.
 */

export type TodayReminderRecord = {
  id: string;
  title: string;
  childName: string;
  /** ISO-8601 instant the reminder would be shown. */
  fireAt: string;
  /** ISO-8601 instant the arrangement starts. */
  dueAt: string;
};

export type TodayReminderSource =
  | { kind: "listed"; records: readonly TodayReminderRecord[] }
  | { kind: "unsynced" };

export type TodayReminderRow = {
  id: string;
  title: string;
  detail: string;
};

export const todayReminderCopy = {
  title: "今日提醒",
  inPageOnly: "这个列表只显示在页面里。不会申请通知权限，不会发系统通知，也不会弹出横幅。",
  emptyUnsynced: "今天没有可在这个页面列出的提醒。",
  emptyListed: "今天没有提醒。",
  boundaryUnsynced: "手机上的提醒按那台设备里已确认的安排排期，不经过服务器。网页读不到那些提醒，也不会在浏览器里另记一份。",
  boundaryListed: "这里只是页面里的列表，不会发系统通知。",
} as const;

/** The only source the Web page is allowed to use. */
export function sharedTodayReminderSource(): TodayReminderSource {
  return { kind: "unsynced" };
}

export function localDayKey(instant: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(instant);
}

function clock(iso: string, timeZone: string): string {
  const instant = new Date(iso);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone,
      month: "numeric",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    }).formatToParts(instant).map((part) => [part.type, part.value]),
  );
  return `${parts.month}月${parts.day}日 ${parts.hour}:${parts.minute}`;
}

export function todayReminderRows(source: TodayReminderSource, now: Date, timeZone: string): TodayReminderRow[] {
  if (source.kind !== "listed") return [];
  const day = localDayKey(now, timeZone);
  return source.records
    .filter((record) => {
      const fire = new Date(record.fireAt);
      const due = new Date(record.dueAt);
      return !Number.isNaN(fire.getTime()) && !Number.isNaN(due.getTime()) && localDayKey(fire, timeZone) === day && record.title.trim() !== "";
    })
    .slice()
    .sort((a, b) => a.fireAt.localeCompare(b.fireAt) || a.id.localeCompare(b.id))
    .map((record) => ({
      id: record.id,
      title: record.title.trim(),
      detail: `${record.childName.trim()} · 将在 ${clock(record.fireAt, timeZone)} 提醒 · ${clock(record.dueAt, timeZone)} 开始`,
    }));
}

export function todayReminderView(source: TodayReminderSource, now: Date, timeZone: string) {
  const rows = todayReminderRows(source, now, timeZone);
  if (source.kind === "unsynced") {
    return {
      availability: "unsynced" as const,
      rows,
      empty: todayReminderCopy.emptyUnsynced,
      note: todayReminderCopy.inPageOnly,
      boundary: todayReminderCopy.boundaryUnsynced,
    };
  }
  return {
    availability: "listed" as const,
    rows,
    empty: rows.length === 0 ? todayReminderCopy.emptyListed : null,
    note: todayReminderCopy.inPageOnly,
    boundary: todayReminderCopy.boundaryListed,
  };
}
