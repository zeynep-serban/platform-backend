# etl-worker

Workcube reporting ETL worker — schema-service contract consumer.
Adım 12 of the reporting refactor plan (see
`docs/plan-reporting-refactor-2026-05-14.md` in `platform-k8s-gitops`).

## Status — PR-1 (scaffold)

This is the first slice of Adım 12. PR-1 covers **only** the
schema-service HTTP contract client + typed exceptions + pytest /
ruff / mypy gate. Live ETL work (runner orchestration, MSSQL reads,
reports_db inserts, K8s Job manifest, Docker image) lands in
subsequent PRs.

| Slice | Scope | Status |
|---|---|---|
| PR-1 | `SchemaServiceClient` + contract models + tests + CI gate | ✅ merged ([#205](https://github.com/Halildeu/platform-backend/pull/205)) |
| PR-2a | Config / CLI / client wiring (`SCHEMA_SERVICE_URL`, internal key env, schema/scope args, typed exit behaviour) | this PR |
| PR-2b | Runner orchestration (retry, audit, resume, DB lifecycle) | pending |
| PR-3 | Dockerfile + K8s Job manifest + test cluster wiring | pending |
| PR-4 | Live smoke against testai schema-service + reports DB writes | pending (operator gate) |

## PR-2a — CLI usage (this slice)

Console script:

```
$ etl-worker --help
usage: etl-worker [-h] {fetch-snapshot} ...

$ etl-worker fetch-snapshot --help
usage: etl-worker fetch-snapshot [-h] [--schema SCHEMA] [--timeout TIMEOUT]
```

Environment variables (`Config.from_env()`):

| Variable | Required | Default | Notes |
|---|---|---|---|
| `SCHEMA_SERVICE_URL` | ✅ | — | http / https only; no embedded credentials; trailing slash trimmed |
| `SCHEMA_SERVICE_INTERNAL_API_KEY` | — | unset (passthrough) | Sent as `X-Internal-Api-Key` header |
| `SCHEMA_SERVICE_TIMEOUT_SECONDS` | — | `10` | Positive float |
| `SCHEMA_SERVICE_SCHEMA` | — | unset | Default for `?schema=` selector; `--schema` CLI flag overrides |
| `SCHEMA_SERVICE_CONTRACT_VERSIONS` | — | `1` | CSV of accepted versions, e.g. `1,2` |

Success output: one-line JSON on stdout

```json
{"contract_version":"1","allowlist_name":"ReportingAllowlist","allowlist_version":"V1","table_count":42,"column_count":312}
```

Error output: one-line human-readable message on stderr + sysexits-style exit code:

| Exit code | Constant | Trigger | Caller action |
|---:|---|---|---|
| `0` | `EX_OK` | Snapshot fetched + parsed | Continue pipeline |
| `64` | `EX_USAGE` | Bad CLI args, missing config, invalid URL, bad timeout, empty CSV | Abort — fix the invocation |
| `70` | `EX_SOFTWARE` | 4xx response, parse failure, malformed body | Abort — fix the target contract / schema-service shape |
| `75` | `EX_TEMPFAIL` | 5xx response or transport-level outage | Retry with backoff |
| `76` | `EX_PROTOCOL` | `contract_version` not in `SCHEMA_SERVICE_CONTRACT_VERSIONS` | Abort — operator must reconcile |

> The command validates the **Adım 12 target contract**; live wiring
> against the production schema-service shape waits for the
> schema-service target-shape PR (`version` → `contract_version`,
> `tables` Map → list, `dataType` → `type`, plus
> `allowlist_name` / `allowlist_version` emission).

## Target contract — *not* the current schema-service response

> Codex thread `019e2a5c` post-impl review explicitly called this out as
> a blocker: the consumer side must not pretend it mirrors today's
> schema-service shape.

Today `GET /api/v1/schema/snapshot` answers with
([`schema-service/src/main/java/com/example/schema/model/SchemaSnapshot.java`](../schema-service/src/main/java/com/example/schema/model/SchemaSnapshot.java)
and `ColumnInfo.java`):

```jsonc
{
  "version": "…",                  // free-form string, not contract_version
  "metadata": { … },               // host / database / schema / extractedAt / counts
  "tables": {                      // MAP keyed by table name, not list
    "EMPLOYEES": {
      "columns": [
        { "name": "…", "dataType": "…", "nullable": true, … }   // dataType, not type
      ],
      …
    }
  },
  "relationships": [ … ],
  "domains": { … },
  "analysis": { … }
}
```

The model the etl-worker consumer expects is the Adım 12 **target**
shape (PR-2+ must land schema-service-side changes before live wiring):

```jsonc
{
  "contract_version": "1",
  "allowlist_name": "ReportingAllowlist",
  "allowlist_version": "V1",
  "tables": [                      // LIST, not map
    {
      "schema": "dbo",
      "name": "EMPLOYEES",
      "columns": [
        { "name": "EMPLOYEE_ID", "type": "int", "nullable": false }
      ]
    }
  ]
}
```

PR-1 ships the consumer + unit tests so the schema-service contract
evolution has a concrete acceptance target. Until schema-service
emits the target shape, the runner (PR-2b) stays unconfigured against
live schema-service — the consumer raises
`SchemaServiceMalformedResponse` on the current production shape,
which is the intended fail-closed behaviour.

## Service-to-service auth

The client honours the same auth pathway as the existing report-service
Java client
([`SchemaServiceClient.java`](../report-service/src/main/java/com/example/report/schema/tier/SchemaServiceClient.java))
and the schema-service controller
([`SchemaController.java`](../schema-service/src/main/java/com/example/schema/controller/SchemaController.java)):

- `internal_api_key` constructor argument → sent as
  `X-Internal-Api-Key` header on every request.
- Optional `?schema=<name>` query parameter via
  `fetch_snapshot(schema=…)` for selecting parametric / yearly
  `workcube_mikrolink_<year>` shards.
- Absent key still works against a schema-service whose
  `INTERNAL_API_KEY_HEADER` configuration is blank (test / dev
  passthrough) — production deployments must set the key via Vault /
  ESO.

## Provisional location decision

The worker lives at `platform-backend/etl-worker/` for now — top-level
within the platform-backend monorepo. Rationale:

- Top-level rather than nested under `report-service/` because the
  worker is a runtime Job, not a report-service child. It consumes
  schema-service and writes reports_db; report-service runtime is
  separate.
- Monorepo subdirectory rather than a brand-new `Halildeu/etl-worker`
  repo because new GitHub repo creation is a user-level decision under
  CLAUDE.md. If a later ADR moves this out, `git filter-repo` can
  carve out the history cleanly.

## Layout

```
etl-worker/
├── README.md
├── pyproject.toml
├── etl_worker/
│   ├── __init__.py
│   ├── __main__.py              # `python -m etl_worker` entry point
│   ├── cli.py                   # argparse CLI + exit-code contract
│   ├── config.py                # Config.from_env() + ConfigError
│   ├── contracts.py             # SchemaSnapshot / TableSpec / ColumnSpec dataclasses
│   └── schema_service_client.py # SchemaServiceClient + typed exceptions
└── tests/
    ├── test_cli.py              # 16 CLI cases (override precedence + exit-code matrix)
    ├── test_config.py           # 23 Config.from_env validation cases
    └── test_schema_service_client.py
```

## Local checks

```
cd etl-worker
python -m pip install -e '.[dev]'
python -m pytest
python -m ruff check etl_worker tests
python -m mypy etl_worker
```

CI runs the same three gates on every push that touches
`etl-worker/**` via `.github/workflows/etl-worker.yml`.

## Runtime dependencies

PR-1 stays **stdlib-only** at runtime (only `urllib.request`). Dev
dependencies are pinned via `[project.optional-dependencies]` `dev`:
`pytest`, `pytest-cov`, `ruff`, `mypy`. New runtime dependencies are
added only when the runner / live ETL slices land, and each one needs
explicit review.

## What PR-1 does NOT do

- No Dockerfile.
- No K8s manifest.
- No live schema-service HTTP call.
- No runner orchestration / scheduling.
- No reports_db connection or insert.
- No parametric / yearly schema enablement claim.

These all belong to follow-up PRs in the Adım 12 sequence above.

## Cross-AI peer review

Adım 12 PR-1 follows the HARD RULE — Cross-AI Peer Review pattern at
provider level:

- Implementer: Claude (Anthropic)
- Reviewer: Codex (OpenAI) thread `019e2a5c` plan-time AGREE on the
  narrow scope above; post-impl diff review pending on the PR itself.
