import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

const nextConfig: NextConfig = {
  output: "standalone",
  compiler: {
    // prod 构建剥离 console.log/warn/info/debug，保留 console.error 作为兜底错误日志
    removeConsole:
      process.env.NODE_ENV === "production" ? { exclude: ["error"] } : false,
  },
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "asset.actionow.ai",
      },
      {
        protocol: "https",
        hostname: "*.actionow.ai",
      },
      {
        protocol: "https",
        hostname: "asset.alienworm.top",
      },
      {
        protocol: "http",
        hostname: "minio",
        port: "9000",
      },
    ],
  },
  // API proxying is handled by /api/[...path]/route.ts instead of rewrites
  // This is more reliable in Cloudflare Workers environment
};

export default withNextIntl(nextConfig);
