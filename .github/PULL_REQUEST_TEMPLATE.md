## Özet

<!-- Bu PR ne yapıyor? 1-2 cümle. -->

## Scope

- [ ] Service(s): auth / user / variant / core-data / report / schema / api-gateway / discovery-server
- [ ] Zanzibar plane: permission-service / common-auth/openfga / openfga-runtime
- [ ] Infrastructure: pom.xml / Dockerfile / CI workflow
- [ ] Docs: README / CONTRIBUTING / ADR reference

## Test

- [ ] `./mvnw -q -DskipTests test-compile` PASS
- [ ] `./mvnw -q -DskipTests -pl <service> -am verify` PASS
- [ ] CI `ci-mvn-check` GREEN

## Dual-build etkisi (Faz 19.8'e kadar)

- [ ] platform-ssot'ta denk/paralel değişiklik var mı?
- [ ] Image ref değişikliği platform-k8s-gitops digest pin bozmaz

## Zanzibar koruma (permission-service / common-auth/openfga için)

- [ ] OpenFGA model/store-id değişmedi, veya değiştiyse fixture doğrulandı
- [ ] Scoped allow seed fixtures compat

## Close-out Discipline (R16)

<!-- Bu PR FAZ/Program/Adım deliverable'ı tamamlıyor mu? Aşağıdaki gap'lerden hangileri kapatıldı? -->

- [ ] **Deferred sub-item var mı?** Varsa registry/issue link: <!-- e.g. RC-009 deferred-stub-rules.yaml entry, R15 follow-up issue -->
- [ ] **Stub / no-op return** (`return List.of();` davranışsız body) var mı? Varsa `report-service/src/main/resources/contract/deferred-stub-rules.yaml` entry + expiry + owner: <!-- yes/no -->
- [ ] **Authz contract etkisi** var mı (yeni reportGroup / module / action / report tipi)?
  - [ ] OpenFGA `model.fga` `type X` eklendi mi?
  - [ ] Flyway tuple seed (`V*__add_*_tuples.sql`) eklendi mi?
  - [ ] `fga test` assertion (Faz: PR-B sonrası zorunlu) var mı?
- [ ] **Runtime / browser smoke** proof gerekiyor mu? Varsa link: <!-- screenshot / kubectl evidence -->
- [ ] **Cross-AI peer review** (HARD RULE — implementer ≠ reviewer provider): YAML format aşağıda ✓

## Cross-AI

```yaml
implementer_ai: <Claude|Codex|Gemini>
implementer_thread: <session-id>
reviewer_ai: <Claude|Codex|Gemini>
reviewer_thread: <thread-id>
verdict: <agree|partial|revise|red>
```

## Referans

- ADR-0004: split-repo authority transfer
- ADR-0011: authz relation alignment
- ADR-0017: Contract-Registry Cross-Check Pattern (R16 close-out discipline)
- R16 risk: close-out discipline gap (session-handoff-2026-05-14-session-53)
- Related PR: <!-- platform-k8s-gitops veya platform-web -->
