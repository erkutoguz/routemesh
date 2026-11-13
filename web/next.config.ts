import type { NextConfig } from "next";

const pspSimulator = process.env.PSP_SIMULATOR_URL ?? "http://localhost:8085";

const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  async rewrites() {
    return [{ source: "/psp-sim/:path*", destination: `${pspSimulator}/:path*` }];
  },
};

export default nextConfig;
