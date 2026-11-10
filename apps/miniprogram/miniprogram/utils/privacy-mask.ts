type MaskablePage = { setData: (data: Record<string, unknown>) => void };

/**
 * Cover every page in the stack before the app goes to the background, so the
 * WeChat multitask snapshot does not show conversation content (MP-P2).
 */
export function createPrivacyMask(getPages: () => MaskablePage[]) {
  let masked = false;
  function apply(value: boolean) {
    masked = value;
    for (const page of getPages()) page.setData({ privacyMask: value });
  }
  return {
    cover: () => apply(true),
    reveal: () => apply(false),
    isMasked: () => masked,
  };
}
