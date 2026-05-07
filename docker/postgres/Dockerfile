ARG POSTGRES_BASE_IMAGE=pgvector/pgvector:pg15
FROM ${POSTGRES_BASE_IMAGE}

ARG APT_MIRROR=mirrors.aliyun.com
ARG PG_JIEBA_GIT_URL=https://github.com/jaiminpan/pg_jieba.git
ARG PG_JIEBA_GIT_FALLBACK_URL=https://gitee.com/shawnyan/pg_jieba.git
ARG CPPJIEBA_GIT_URL=https://github.com/yanyiwu/cppjieba.git
ARG CPPJIEBA_GIT_FALLBACK_URL=https://gitcode.com/mirrors/yanyiwu/cppjieba.git
ARG LIMONP_GIT_URL=https://github.com/yanyiwu/limonp.git
ARG LIMONP_GIT_FALLBACK_URL=https://gitcode.com/mirrors/yanyiwu/limonp.git

USER root

# Build pg_jieba against PostgreSQL 15 headers
RUN set -eux; \
    if [ -f /etc/apt/sources.list ]; then \
        sed -i "s|deb.debian.org|${APT_MIRROR}|g; s|security.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list; \
    fi; \
    if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
        sed -i "s|http://deb.debian.org/debian|http://${APT_MIRROR}/debian|g; s|http://security.debian.org/debian-security|http://${APT_MIRROR}/debian-security|g" /etc/apt/sources.list.d/debian.sources; \
    fi; \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    git \
    build-essential \
    cmake \
    postgresql-server-dev-15 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp

# Install pg_jieba (manual dependency clone + retry/fallback to avoid unstable submodule network)
RUN set -eux; \
    clone_with_retry() { \
      primary="$1"; fallback="$2"; dest="$3"; \
      for url in "$primary" "$fallback"; do \
        [ -z "$url" ] && continue; \
        for i in 1 2 3; do \
          rm -rf "$dest"; \
          if git clone --depth=1 "$url" "$dest"; then \
            return 0; \
          fi; \
          sleep 2; \
        done; \
      done; \
      return 1; \
    }; \
    clone_with_retry "${PG_JIEBA_GIT_URL}" "${PG_JIEBA_GIT_FALLBACK_URL}" /tmp/pg_jieba; \
    rm -rf /tmp/pg_jieba/libjieba; \
    clone_with_retry "${CPPJIEBA_GIT_URL}" "${CPPJIEBA_GIT_FALLBACK_URL}" /tmp/pg_jieba/libjieba; \
    rm -rf /tmp/pg_jieba/libjieba/deps/limonp; \
    mkdir -p /tmp/pg_jieba/libjieba/deps; \
    clone_with_retry "${LIMONP_GIT_URL}" "${LIMONP_GIT_FALLBACK_URL}" /tmp/pg_jieba/libjieba/deps/limonp; \
    cd /tmp/pg_jieba; \
    PG_CONFIG=/usr/lib/postgresql/15/bin/pg_config; \
    PG_INCLUDE_DIR="$(${PG_CONFIG} --includedir)"; \
    PG_TYPE_INCLUDE_DIR="$(${PG_CONFIG} --includedir-server)"; \
    PG_LIB_DIR="$(${PG_CONFIG} --libdir)"; \
    cmake -S . -B build \
      -DPostgreSQL_PG_CONFIG=${PG_CONFIG} \
      -DPostgreSQL_INCLUDE_DIR=${PG_INCLUDE_DIR} \
      -DPostgreSQL_TYPE_INCLUDE_DIR=${PG_TYPE_INCLUDE_DIR} \
      -DPostgreSQL_LIBRARY=${PG_LIB_DIR}/libpq.so; \
    cmake --build build -j"$(nproc)"; \
    cmake --install build; \
    rm -rf /tmp/pg_jieba

USER postgres
