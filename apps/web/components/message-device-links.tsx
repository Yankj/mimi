"use client";

import { useMemo, useState } from "react";
import { copy } from "@/lib/copy";
import {
  downloadTextFile,
  findEntities,
  isSafeDeviceUrl,
  openDeviceUrl,
  type DetectedEntity,
} from "@/lib/device-actions";

export function MessageDeviceLinks({ text, complete }: { text: string; complete: boolean }) {
  const entities = useMemo(() => (complete ? findEntities(text) : []), [complete, text]);
  const [pending, setPending] = useState<DetectedEntity | null>(null);
  if (!complete || entities.length === 0) return null;

  function confirm() {
    if (!pending) return;
    if (pending.type === "datetime") downloadTextFile(pending.filename, pending.ics);
    else if (isSafeDeviceUrl(pending.url)) openDeviceUrl(pending.url);
    setPending(null);
  }

  return (
    <>
      <div className="actions device-links" aria-label={copy.deviceLinks}>
        {entities.map((entity) => (
          <button key={`${entity.type}:${entity.start}:${entity.text}`} type="button" onClick={() => setPending(entity)}>
            {entity.label}
          </button>
        ))}
      </div>
      {pending ? (
        <div className="dialog-back" role="dialog" aria-modal="true" aria-label={pending.type === "datetime" ? copy.deviceDownloadIcs : copy.deviceLinkConfirm}>
          <div className="dialog">
            <h2>{pending.type === "datetime" ? copy.deviceDownloadIcs : copy.deviceLinkConfirm}</h2>
            <p>{pending.label}</p>
            {pending.type === "datetime" ? <p className="quiet">{copy.deviceIcsFootnote}</p> : null}
            <div className="actions">
              <button className="primary" type="button" onClick={confirm}>{pending.type === "datetime" ? copy.deviceDownloadIcs : copy.deviceOpen}</button>
              <button type="button" onClick={() => setPending(null)}>{copy.cancel}</button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
