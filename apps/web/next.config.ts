import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  transpilePackages: ["@mimi/core"],
  reactStrictMode: true,
  poweredByHeader: false,
  allowedDevOrigins: ["127.0.0.1"],
};

export default nextConfig;
