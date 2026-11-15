/**
 * Re-exports the shared request builders. Web behavior is unchanged;
 * the implementation lives in `@mimi/core`.
 * Web also re-exports its own device-tool list so callers can opt into the headers.
 */
export * from "@mimi/core/wire";
export { WEB_DEVICE_TOOLS } from "./device-actions";
