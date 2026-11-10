import { readPublicConfig, type PublicConfig, uploadHostsFromConfig } from "./wire";

export function readServerConfig(): PublicConfig | null {
  return readPublicConfig({
    NEXT_PUBLIC_SUPABASE_URL: process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL,
    NEXT_PUBLIC_SUPABASE_ANON_KEY: process.env.SUPABASE_ANON_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
    NEXT_PUBLIC_CATPAW_EDGE_ORIGIN: process.env.CATPAW_EDGE_ORIGIN || process.env.NEXT_PUBLIC_CATPAW_EDGE_ORIGIN,
  });
}

export function serverUploadHosts(config: PublicConfig) {
  return uploadHostsFromConfig(config);
}
