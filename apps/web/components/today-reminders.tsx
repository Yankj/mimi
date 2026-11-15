"use client";

import type { ReactNode } from "react";
import { copy } from "@/lib/copy";
import { sharedTodayReminderSource, todayReminderView, type TodayReminderSource } from "@/lib/today-reminders";

function Bell() {
  return (
    <svg className="remind-bell" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 9.5a6 6 0 1 1 12 0c0 5 2 6.5 2 6.5H4S6 14.5 6 9.5z" />
      <path d="M10 18.5a2 2 0 0 0 4 0" />
    </svg>
  );
}

/** In-page list. Hidden while family sync is off, so the page does not invent rows. */
export function TodayReminders({
  source = sharedTodayReminderSource(),
  now = new Date(),
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
  hiddenNote = null,
}: {
  source?: TodayReminderSource;
  now?: Date;
  timeZone?: string;
  /** Shown only while family sync is off. The list title is not rendered. */
  hiddenNote?: ReactNode;
}) {
  const view = todayReminderView(source, now, timeZone);
  if (!view.visible) return hiddenNote;
  return (
    <section className="card today-reminders" aria-labelledby="today-reminders-title">
      <h2 id="today-reminders-title">{copy.todayReminders}</h2>
      {view.rows.length === 0 ? (
        <p>{view.empty}</p>
      ) : (
        <ul className="reminder-list">
          {view.rows.map((row) => (
            <li key={row.id}>
              <Bell />
              <div>
                <strong>{row.title}</strong>
                <div className="quiet">{row.detail}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
      <p className="phone-note"><strong>{copy.todayRemindersPhone}</strong> {copy.todayRemindersFootnote}</p>
    </section>
  );
}
