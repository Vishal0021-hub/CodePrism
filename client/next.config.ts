import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // standalone mode: packages only the minimum needed to run in Docker.
  // The Docker build copies .next/standalone and .next/static to the image.
  output: "standalone",
};

export default nextConfig;
