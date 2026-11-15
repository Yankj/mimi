"use client";

import { sharedTodayReminderSource, todayReminderCopy, todayReminderView } from "@/lib/today-reminders";

/** Document-flow list. It does not request notification permission or post a banner. */
export function TodayReminders({
  now = new Date(),
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
}: {
  now?: Date;
  timeZone?: string;
}) {
  const view = todayReminderView(sharedTodayReminderSource(), now, timeZone);
  return (
    <section className="card today-reminders" aria-labelledby="today-reminders-title">
      <h2 id="today-reminders-title">{todayReminderCopy.title}</h2>
      <p className="quiet">{view.note}</p>
      <ul className="reminder-list">
        {view.rows.length === 0 ? <li>{view.empty}</li> : view.rows.map((row) => (
          <li key={row.id}>
            <strong>{row.title}</strong>
            <span className="quiet">{row.detail}</span>
          </li>
        ))}
      </ul>
      <p className="quiet">{view.boundary}</p>
    </section>
  );
}
