# etl-worker container image — Adım 12 PR-3b.
#
# Codex `019e2a5c` PR-3 plan-time AGREE on Opt-B′ sequence:
# PR-3a backend adapter/config/CLI (merged), PR-3b Dockerfile +
# image build/push CI (this slice), PR-3c gitops manifest update
# with the immutable digest pin produced here.
#
# Design contract:
#
# * Python 3.12 slim base — matches `pyproject.toml` `requires-python`.
# * Multi-stage build: dedicated builder installs the package into
#   `/install`, runtime copies only the resolved tree. No build
#   toolchain, no wheel cache in the runtime layer.
# * `psycopg[binary]` wheel ships its own `libpq`, so the runtime
#   stage needs no extra system libpq layer (Codex AGREE on PR-3a
#   binary-wheel tradeoff; reassess only if prod policy bans wheel).
# * Non-root user `etl:etl` (UID/GID 1000) matches the platform K8s
#   PSS restricted profile + the existing `kustomize/base/apps/etl-worker/`
#   `securityContext.runAsUser=1000`.
# * `ENTRYPOINT ["etl-worker"]` is the console script declared in
#   `pyproject.toml`; the K8s Job manifest (PR-3c) supplies the real
#   `run --reports-db postgres ...` args.
# * Default `CMD ["--help"]` so a bare `docker run <image>` proves the
#   console script wired up without exercising any external dependency
#   (no schema-service, no DB).

# ---- Stage 1: builder ----------------------------------------------------
FROM python:3.12-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /src

# Copy package source. We deliberately avoid a `.dockerignore` here so
# `pyproject.toml` always lands first (build kernel layer cache key)
# even if test/etc files churn.
COPY etl-worker/pyproject.toml etl-worker/README.md ./
COPY etl-worker/etl_worker ./etl_worker

# Install into a relocatable prefix so the runtime stage can copy just
# the resolved tree without dragging in pip / build tooling.
RUN python -m pip install --upgrade pip \
 && python -m pip install --prefix=/install .


# ---- Stage 2: runtime ----------------------------------------------------
FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH=/install/bin:$PATH \
    PYTHONPATH=/install/lib/python3.12/site-packages

# Non-root user to match K8s `runAsUser: 1000` in
# `kustomize/base/apps/etl-worker/job.yaml`.
RUN groupadd --gid 1000 --system etl \
 && useradd  --uid 1000 --gid 1000 --system --shell /usr/sbin/nologin etl

# Copy resolved package tree + the console-script entry point from
# the builder stage.
COPY --from=builder /install /install

USER 1000:1000

# Provenance label (Codex 019e2a5c PR-3a thread reference) so the
# image carries a pointer back to the design AGREE.
LABEL org.opencontainers.image.source="https://github.com/Halildeu/platform-backend" \
      org.opencontainers.image.description="Workcube reporting ETL worker — schema-service contract consumer." \
      org.opencontainers.image.licenses="Proprietary" \
      platform.acik.com/codex-thread="019e2a5c" \
      platform.acik.com/adim="12-pr3b"

ENTRYPOINT ["etl-worker"]
CMD ["--help"]
