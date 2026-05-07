let userConfig = undefined
try {
  userConfig = await import('./v0-user-next.config')
} catch (e) {
  // ignore error
}

const DEFAULT_BACKEND_PORT =
  process.env.NEXT_PUBLIC_BACKEND_PORT || process.env.BACKEND_PORT || '8088'

function trimTrailingSlash(url) {
  return url.replace(/\/+$/, '')
}

function resolveInternalApiBaseUrl() {
  const internalApiBaseUrl = process.env.INTERNAL_API_BASE_URL?.trim()
  if (internalApiBaseUrl && !internalApiBaseUrl.startsWith('/')) {
    return trimTrailingSlash(internalApiBaseUrl)
  }

  return `http://localhost:${DEFAULT_BACKEND_PORT}/api`
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  experimental: {
    webpackBuildWorker: true,
    parallelServerBuildTraces: true,
    parallelServerCompiles: true,
  },
  webpack: (config, { dev }) => {
    if (dev) {
      // 禁用错误覆盖层
      config.devtool = false;
    }
    return config;
  },
  // 确保静态资源路径正确
  trailingSlash: false,
  generateEtags: false,
  async rewrites() {
    const apiBaseUrl = resolveInternalApiBaseUrl()
    return [
      {
        source: '/api/:path*',
        destination: `${apiBaseUrl}/:path*`,
      },
    ]
  },
}

mergeConfig(nextConfig, userConfig)

function mergeConfig(nextConfig, userConfig) {
  if (!userConfig) {
    return
  }

  for (const key in userConfig) {
    if (
      typeof nextConfig[key] === 'object' &&
      !Array.isArray(nextConfig[key])
    ) {
      nextConfig[key] = {
        ...nextConfig[key],
        ...userConfig[key],
      }
    } else {
      nextConfig[key] = userConfig[key]
    }
  }
}

export default nextConfig
