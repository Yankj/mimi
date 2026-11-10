export type WebProfile = {
  userId: string;
  email: string | null;
  scopeId: string;
  uploadHosts: string[];
  provider: "google" | "github" | null;
  locked: boolean;
};
