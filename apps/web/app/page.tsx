import { AppShell } from "@/components/app-shell";
import { readBrandAssets } from "@/lib/read-brand-assets";

export default function HomePage() {
  return <AppShell brand={readBrandAssets()} />;
}
