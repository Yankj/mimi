"use client";

import { copy } from "@/lib/copy";
import { deviceActionFromTool, type DeviceAction } from "@/lib/device-actions";
import { asRecord, stringField } from "@/lib/wire";

export type DeviceCallState = "confirming" | "running" | "done" | "phoneOnly" | "expired" | "cancelled";

export function deviceCallFromCard(raw: Record<string, unknown>, now = new Date()) {
  const tool = stringField(raw, "tool");
  const args = asRecord(raw.args) ?? {};
  const expires = stringField(raw, "expires_at");
  const expired = Boolean(expires && !Number.isNaN(Date.parse(expires)) && Date.parse(expires) <= now.getTime());
  return { tool, args, expired, action: deviceActionFromTool(tool, args, now) };
}

export function DeviceCallCard({
  raw,
  state,
  receipt,
  onConfirm,
  onCancel,
  onAcknowledge,
}: {
  raw: Record<string, unknown>;
  state: DeviceCallState;
  receipt?: string;
  onConfirm: (action: DeviceAction) => void;
  onCancel: () => void;
  onAcknowledge: () => void;
}) {
  const { expired, action } = deviceCallFromCard(raw);
  const phase = expired && state === "confirming" ? "expired" : state;
  return (
    <section className="card device-call">
      <strong>{action.title}</strong>
      {action.fields.map((field) => (
        <p key={field.key} className="device-field">
          <span className="quiet">{field.label}</span>
          <span>{field.value}</span>
        </p>
      ))}
      {phase === "confirming" && action.kind === "link" && action.footnote ? <p className="quiet">{action.footnote}</p> : null}
      {phase === "confirming" && action.kind === "ics" ? <p className="quiet">{copy.deviceIcsFootnote}</p> : null}
      {phase === "confirming" && action.kind === "phoneOnly" ? <p>{copy.devicePhoneOnlyBody}</p> : null}
      {phase === "running" ? <p className="quiet">{copy.deviceRunning}</p> : null}
      {phase === "expired" ? <p className="quiet">{copy.deviceExpired}</p> : null}
      {phase === "done" && receipt ? <p>{receipt}</p> : null}
      {phase === "cancelled" ? <p className="quiet">{copy.cancel}</p> : null}
      {phase === "phoneOnly" ? (
        <div className="actions">
          <p>{copy.devicePhoneOnly}</p>
          <button type="button" onClick={onAcknowledge}>{copy.deviceKnow}</button>
        </div>
      ) : null}
      {phase === "confirming" && action.kind === "phoneOnly" ? (
        <div className="actions">
          <button type="button" onClick={onAcknowledge}>{copy.deviceKnow}</button>
          <button type="button" onClick={onCancel}>{copy.cancel}</button>
        </div>
      ) : null}
      {phase === "confirming" && action.kind !== "phoneOnly" ? (
        <div className="actions">
          <button className="primary" type="button" onClick={() => onConfirm(action)}>{action.confirmLabel}</button>
          <button type="button" onClick={onCancel}>{copy.cancel}</button>
        </div>
      ) : null}
    </section>
  );
}
