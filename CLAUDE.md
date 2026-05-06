# platform-backend — Canonical Backend Repo

> **Halildeu/platform-backend** is the canonical Java backend monorepo (Faz 19, ADR-0004). All backend code/changes live here. Ops repo is `platform-k8s-gitops`; frontend is `platform-web`.

---

## HARD RULE — `platform-ssot` is DEPRECATED, code there is YASAK (2026-05-06)

`Halildeu/platform-ssot` is **DEPRECATED, audit-only**. Faz 19 split-repo authority transfer completed 2026-04-25.

**Do NOT:**
- commit code to `platform-ssot`
- open PRs against `platform-ssot`
- modify Dockerfile/workflow files in `platform-ssot`
- add governance feature contracts to `platform-ssot`

**Why:** ssot's GHCR push rights for `platform-backend-*` packages have been **revoked** (403 Forbidden). Any image build there is orphaned — never reaches the cluster. Live evidence: deploy-backend run `25408778230` failed with:

```
failed to push ghcr.io/halildeu/platform-backend-api-gateway:sha-...
unexpected status from HEAD request: 403 Forbidden
```

**Repo mapping:**

| Old (`platform-ssot`) | Canonical |
|---|---|
| `backend/<service>/` | `platform-backend/<service>/` (this repo) |
| `web/apps/mfe-*/` | `platform-web/apps/mfe-*/` |
| `kustomize/`, `argocd/` | `platform-k8s-gitops/` |
| `extensions/PRJ-PM-SUITE/contract/` | governance: see canonical authority docs |

**If you need an ssot reference:** read-only is fine (`gh api repos/Halildeu/platform-ssot/contents/<path>` or local clone). Never write back.

**Existing ssot PRs as audit residue:** Several PRs (#561/#564/#567/#568/#570/#571/#572) merged in ssot 2026-05-05 never reached the cluster (GHCR 403). Their diffs were re-applied in canonical repos:
- platform-backend PR #63: AuthCookieEndpoint /refresh path matcher (from ssot PR #571)
- platform-web PR #257: muavin v3 frontend (X-Company-Id + filter forwarding + CompanyPicker, from ssot PR #564 + #570)
- platform-backend muavin v3 mega PR: pending

---

## Build / deploy

- Multi-module Maven, Java 21, Docker buildx → GHCR `ghcr.io/halildeu/platform-backend-<service>`
- Workflow: `.github/workflows/ci-image-build.yml`
- Deploy: `platform-k8s-gitops` overlay digest pin → ArgoCD/manual `kubectl apply`

---

## Cross-AI work

- Code Claude wrote → review by separate channel (Codex new thread, third-AI, or human). Self-review is forbidden (CNS-011 self-fulfilling loop).
- Live smoke is mandatory after image rollout — CI green alone is not sufficient.
