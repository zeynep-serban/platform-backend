# User Impersonation v1 — Spec

> **Status**: DRAFT (Codex `019e0dfb` iter-2 AGREE consensus)
> **Owner**: halil@platform.local
> **Codex thread**: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
> **Created**: 2026-05-09
> **Target sprints**: ~3-4 (15-20 iş günü), MVP + security review + audit dashboard

## 0. TL;DR

Admin (SuperAdmin) kullanıcılar, başka bir kullanıcının kimliğini geçici olarak üstlenip ("impersonate") sistem yetkilerini o kullanıcı gibi test edebilir. Yapı **A2 backend-brokered Keycloak Token Exchange (RFC 8693)**: confidential `impersonation-broker` client backend tarafında, frontend public client'a token-exchange açılmaz. Her impersonation event audit trail'e kayıtlanır (impersonator + target + actions). Frontend shell'de sticky red banner, multi-tab sync, event-driven cache flush.

## 1. Motivasyon ve kapsam

### 1.1 Problem

- Yetki testi için sürekli login/logout yapmak operasyon yükü
- Workcube ERP entegre platformda 16 rol × N kullanıcı kombinasyonu test edilmesi gerekli
- Müşteri destek senaryolarında "kullanıcı bunu görüyor mu?" sorusunu hızlı yanıtlama
- Audit/compliance: gerçek aktör kaybolmadan başka kullanıcı oturumunu inceleme

### 1.2 Hedefler (MVP)

- ✅ SuperAdmin → herhangi bir non-privileged user'ı impersonate edebilir
- ✅ Reason field zorunlu (min 10 karakter; audit traceability)
- ✅ Denylist: super-admin, security-admin asla impersonate edilemez (privilege escalation guard)
- ✅ Global sticky banner (red top-bar, "⚠ <user> olarak görüntülüyorsunuz [Çıkış]")
- ✅ Multi-tab sync (BroadcastChannel/storage event)
- ✅ Stop button → orijinal admin token'a güvenli geri dönüş
- ✅ Audit: IMPERSONATION_STARTED/STOPPED/FAILED/BLOCKED + her impersonated event impersonator+target context kolonlarıyla etiketlenmiş
- ✅ Cache flush event-driven (auth epoch bump + react-query + Zanzibar + reporting metadata + X-Company-Id reset)

### 1.3 Hedef DEĞİL (post-MVP)

- ❌ Granular admin policy (Admin sadece kendi şirket scope'undaki user) — Faz 2
- ❌ Audit dashboard rapor sayfası (PR-D, MVP sonrası)
- ❌ Workcube ERP-side session yansıması (Workcube paralel oturum bağımsız kalır)
- ❌ Persistent impersonation (sayfa kapatıldıktan sonra devam) — token süreli
- ❌ Cross-tenant impersonation (tenant A admin'i tenant B user impersonate) — security risk
- ❌ Step-up authentication (MFA challenge impersonation start öncesi) — Faz 2

## 2. Mimari karar — A2 backend-brokered

### 2.1 Reddedilen alternatifler

| Seçenek | Sebep |
|---|---|
| **A** Frontend public client direct token-exchange | Geniş privilege surface (SPA bundle'da broker secret), audit aktörünü kaybeder |
| **B** Custom backend impersonate endpoint + JWT signing | JWK rotation kompleks, Keycloak SSO ile çelişki |
| **C** Frontend `X-Impersonate-User` header | Backend her endpoint'te admin check + authz swap; audit trail kompleks; security mass surface |
| **D** Çoklu browser pencere | UX kötü, paralel test zor |

### 2.2 Seçilen: A2 backend-brokered Keycloak Token Exchange

```
[Frontend mfe-shell]
    │ POST /api/v1/impersonation/sessions { targetUserId, reason }
    │ Authorization: Bearer <admin_jwt>
    ▼
[auth-service broker endpoint]
    │ 1. Admin role + permission check (SuperAdmin)
    │ 2. Target user denylist + enabled check
    │ 3. Reason validation (min 10 char)
    │ 4. Insert impersonation_sessions row (PG)
    ▼
[Keycloak token exchange]
    │ POST /realms/platform-test/protocol/openid-connect/token
    │   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
    │   client_id=impersonation-broker  (confidential)
    │   client_secret=<vault>
    │   subject_token=<admin_jwt>
    │   requested_subject=<target_user_id>
    │   audience=frontend
    ▼
[Exchanged token]
    │ sub=<target_user_id>
    │ act={ sub: <admin_user_id>, ... }  (impersonator claim)
    │ impersonation_session_id=<uuid>  (custom mapper, broker-managed)
    │ exp=15min  (kısa ömürlü, refresh off)
    ▼
[Frontend storage]
    │ effective_token = <exchanged>  (cookie + Redux state)
    │ original_token  = preserved in Keycloak adapter SSO session
    │ impersonation.active = true (Redux)
    │ BroadcastChannel: 'impersonation-state-change'
    ▼
[Cache flush event chain]
    │ auth.bumpEpoch()       — invalidate per-user metadata caches
    │ permissionProvider.refresh()
    │ queryClient.clear()    — react-query
    │ zanzibarCache.clear()
    │ reportingMetadataCache.clear()
    │ companyHeaderState.reset()  — X-Company-Id NULL, target user'dan re-pick
    ▼
[Subsequent API calls]
    │ Authorization: Bearer <effective_token>
    │ Backend: act claim PARSE + audit context propagation
```

### 2.3 Stop impersonation flow

```
[Frontend Stop button]
    │ DELETE /api/v1/impersonation/sessions/current
    │ Authorization: Bearer <effective_token>  (target+act)
    ▼
[auth-service broker]
    │ 1. Validate session + impersonator match
    │ 2. UPDATE impersonation_sessions SET stopped_at=now, stop_reason=USER_INITIATED
    │ 3. Emit IMPERSONATION_STOPPED audit event
    ▼
[Frontend]
    │ keycloak.updateToken()  — original admin token tazele (silent SSO)
    │ effective_token = original_token
    │ impersonation.active = false (Redux)
    │ BroadcastChannel sync to other tabs
    │ Cache flush again (back to admin context)
    ▼
[Subsequent calls = admin]
```

## 3. Bileşenler

### 3.1 Keycloak / GitOps (PR-A)

#### 3.1.1 Yeni `impersonation-broker` client (confidential)

Test realm: `platform-test`
Prod realm: `serban` (PR-A2 ayrı, açık prod onayı)

Konfigürasyon:
- Client ID: `impersonation-broker`
- Client Authenticator: `Client Id and Secret`
- Service Accounts Enabled: `true`
- Standard Flow Enabled: `false`
- Direct Access Grants Enabled: `false`
- Implicit Flow Enabled: `false`
- Authorization Enabled: `false`
- Token Exchange (under "Permissions" tab): `enabled`

Service account roles:
- Realm role: `impersonation` (mevcut, native Keycloak role)
- Client role (under `realm-management`): `view-users`, `query-users` (target user enabled+notRevoked check için)
- **YASAK**: `realm-admin`, `manage-users`, `manage-clients`, `impersonation` realm-wide (sadece broker scope)

Token exchange policy (Keycloak fine-grained):
- Allowed audiences: `frontend` (test realm), `serban-web` (prod) — token üretilen target client
- Allowed clients to exchange from: yalnızca admin'in mevcut client'ı (`frontend`)
- Allowed users to impersonate: SuperAdmin role group (Keycloak group veya client-role-based policy)

#### 3.1.2 Custom `act` mapper (gerekirse)

Spike çıktısı `act` claim native gelmiyorsa custom protocol mapper:
- Type: `Hardcoded Claim` veya `Script Mapper`
- Token claim name: `act`
- Token claim value: `{ "sub": "<admin_user_id>", "client": "frontend" }`
- Add to ID token: false
- Add to access token: true

Alternatif: `impersonation_session_id` mapper (UUID claim) — backend session table lookup.

#### 3.1.3 GitOps manifest

```yaml
# platform-k8s-gitops kustomize/base/keycloak-config/impersonation-broker.yaml
apiVersion: keycloak.k8s.openshift.io/v1
kind: KeycloakClient
metadata:
  name: impersonation-broker-test
  namespace: platform-test
spec:
  realmSelector:
    matchLabels: { realm: platform-test }
  client:
    clientId: impersonation-broker
    clientAuthenticatorType: client-secret
    secret: ${VAULT:secret/platform/auth-service/impersonation-broker-secret}
    serviceAccountsEnabled: true
    standardFlowEnabled: false
    directAccessGrantsEnabled: false
    implicitFlowEnabled: false
    publicClient: false
    attributes:
      "token.exchange.permission.enabled": "true"
      "token.exchange.audiences": "frontend"
```

(Workcube/Keycloak Operator gerekli; yoksa Terraform Keycloak provider veya manuel realm export/import.)

### 3.2 Backend (PR-B)

#### 3.2.1 auth-service yeni endpoint'ler

```
POST   /api/v1/impersonation/sessions
  body: { targetUserId: long, reason: string (min 10) }
  auth: SuperAdmin only
  resp: 201 { sessionId, exchangedToken, expiresAt, target: {userId, email, displayName}, impersonator: {userId, email} }
  errors:
    400 reason_required
    400 target_blocked (denylist hit)
    400 target_disabled
    400 nested_impersonation_forbidden (act claim varsa)
    403 not_authorized
    404 target_not_found
    409 active_session_exists (single-active-session policy)

DELETE /api/v1/impersonation/sessions/current
  auth: any token with act claim
  resp: 204 + Set-Cookie clearing effective_token
  errors:
    400 no_active_session
    403 session_owner_mismatch

GET    /api/v1/impersonation/sessions/active
  auth: any token
  resp: 200 { sessionId, impersonator, target, startedAt, reason }
       | 204 (no active session)

GET    /api/v1/impersonation/eligible-users?search=&role=&companyId=&page=&pageSize=
  auth: SuperAdmin only
  resp: 200 { items: [{userId, email, displayName, roles[]}], total, page }
  filter: denylist + enabled exclude
```

#### 3.2.2 act claim middleware (auth-service + tüm backend)

```java
// JwtAuthenticationConverter extension
@Component
public class ImpersonationContextExtractor {
    public Optional<ImpersonationContext> extract(Jwt jwt) {
        Map<String, Object> act = jwt.getClaimAsMap("act");
        String sessionId = jwt.getClaimAsString("impersonation_session_id");
        if (act == null && sessionId == null) return Optional.empty();
        // Validate session row exists + not stopped
        // Set RequestContextHolder ImpersonationContext
        return Optional.of(new ImpersonationContext(
            jwt.getSubject(),                      // target user id
            (String) act.get("sub"),                // impersonator user id
            sessionId,
            (Instant) jwt.getClaim("iat")
        ));
    }
}
```

Plus fail-closed gate: backend'in `application.yml`'inde `auth.impersonation.required-act-claim=true` flag — exchanged token'da act/session_id yoksa 401 reject.

#### 3.2.3 Audit migration (Flyway)

```sql
-- V19__impersonation_audit_context.sql (permission-service reports_db)

ALTER TABLE permission_audit_events
  ADD COLUMN impersonation_session_id UUID NULL,
  ADD COLUMN is_impersonated BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN impersonator_user_id BIGINT NULL,
  ADD COLUMN impersonator_subject VARCHAR(255) NULL,
  ADD COLUMN impersonator_email VARCHAR(255) NULL,
  ADD COLUMN target_user_id BIGINT NULL,
  ADD COLUMN target_subject VARCHAR(255) NULL,
  ADD COLUMN target_email VARCHAR(255) NULL,
  ADD COLUMN impersonation_reason VARCHAR(500) NULL;

CREATE INDEX IF NOT EXISTS idx_permission_audit_impersonation_session
  ON permission_audit_events(impersonation_session_id)
  WHERE impersonation_session_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_permission_audit_impersonator
  ON permission_audit_events(impersonator_user_id, occurred_at DESC)
  WHERE impersonator_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_permission_audit_target
  ON permission_audit_events(target_user_id, occurred_at DESC)
  WHERE target_user_id IS NOT NULL;

CREATE TABLE impersonation_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  impersonator_user_id BIGINT NOT NULL,
  impersonator_subject VARCHAR(255) NOT NULL,
  impersonator_email VARCHAR(255),
  target_user_id BIGINT NOT NULL,
  target_subject VARCHAR(255) NOT NULL,
  target_email VARCHAR(255),
  reason VARCHAR(500) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  stopped_at TIMESTAMPTZ,
  stop_reason VARCHAR(50),  -- USER_INITIATED, TOKEN_EXPIRED, ADMIN_REVOKED, ERROR
  exchanged_token_jti VARCHAR(255),  -- for revocation tracking
  ip_address INET,
  user_agent TEXT,
  client_ip_via_xff INET,
  CHECK (impersonator_user_id != target_user_id),
  CHECK (stopped_at IS NULL OR stopped_at >= started_at)
);

CREATE INDEX idx_impersonation_sessions_impersonator
  ON impersonation_sessions(impersonator_user_id, started_at DESC);
CREATE INDEX idx_impersonation_sessions_target
  ON impersonation_sessions(target_user_id, started_at DESC);
CREATE INDEX idx_impersonation_sessions_active
  ON impersonation_sessions(impersonator_user_id)
  WHERE stopped_at IS NULL;
```

#### 3.2.4 Yeni event tipleri

```java
public enum AuditEventType {
    // ... mevcut tipler ...
    IMPERSONATION_STARTED,
    IMPERSONATION_STOPPED,
    IMPERSONATION_FAILED,
    IMPERSONATION_BLOCKED  // denylist hit
}
```

Mevcut event tipleri (REPORT_ACCESS, ROLE_CREATED, vb.) korunur; her event row impersonation context kolonlarıyla etiketlenir (is_impersonated=true ise).

#### 3.2.5 Single-active-session policy (MVP)

Bir admin için aynı anda sadece 1 aktif impersonation session. Yeni session istek → eski session otomatik stop (graceful) veya 409 reject (strict). MVP'de **strict 409** (kullanıcı önce stop'lar).

### 3.3 Frontend (PR-C)

#### 3.3.1 mfe-shell Redux state extension

```ts
// auth.slice.ts ek state
interface ImpersonationState {
  active: boolean;
  sessionId: string | null;
  impersonator: { userId: number; email: string; displayName: string } | null;
  target: { userId: number; email: string; displayName: string; roles: string[] } | null;
  startedAt: string | null;
  reason: string | null;
}

// auth-sync.ts BroadcastChannel payload extension
type AuthSyncMessage =
  | { type: 'token-changed'; token: string }
  | { type: 'logout' }
  | { type: 'impersonation-started'; state: ImpersonationState }
  | { type: 'impersonation-stopped' };
```

#### 3.3.2 User picker modal (shell header dropdown)

```tsx
// apps/mfe-shell/src/widgets/impersonation-picker/UserPickerModal.tsx
<Dialog open={open}>
  <DialogTitle>Test kullanıcı seç</DialogTitle>
  <DialogContent>
    <SearchInput placeholder="Email/isim ara..." />
    <RoleFilter options={roles} />
    <CompanyFilter options={companies} />
    <UserList items={eligibleUsers} onSelect={user => setSelected(user)} />
    <ReasonInput
      required
      minLength={10}
      placeholder="Neden impersonate ediyorsun? (zorunlu)"
    />
    <Button onClick={handleStart} disabled={!selected || !validReason}>
      Bu kullanıcı olarak görüntüle
    </Button>
  </DialogContent>
</Dialog>
```

#### 3.3.3 Global sticky banner

```tsx
// apps/mfe-shell/src/layouts/ImpersonationBanner.tsx
{impersonation.active && (
  <div
    role="alert"
    aria-live="polite"
    className="impersonation-banner"
    data-impersonating="true"
  >
    <Icon name="warning" />
    <span>
      <strong>{impersonation.target.displayName}</strong> ({impersonation.target.email})
      olarak görüntülüyorsunuz
    </span>
    <Button onClick={handleStop} variant="danger" size="sm">
      Çıkış (orijinal kullanıcıya dön)
    </Button>
  </div>
)}
```

CSS:
```css
:root[data-impersonating="true"] .app-shell {
  --shell-impersonation-bg: #b91c1c;
  --shell-impersonation-fg: #ffffff;
  --shell-focus-ring: #ef4444;
}
.impersonation-banner {
  position: sticky;
  top: 0;
  z-index: 9999;
  background: var(--shell-impersonation-bg);
  color: var(--shell-impersonation-fg);
  padding: 8px 16px;
  display: flex;
  gap: 12px;
  align-items: center;
  border-bottom: 2px solid #7f1d1d;
}
```

#### 3.3.4 Cache flush chain

```ts
// shell-services.auth.startImpersonation()
async function startImpersonation(targetUserId: number, reason: string) {
  const resp = await api.post('/api/v1/impersonation/sessions', { targetUserId, reason });
  const { exchangedToken, sessionId, target, impersonator } = resp.data;

  // 1. Token swap
  authStorage.setEffectiveToken(exchangedToken);

  // 2. State update
  dispatch(setImpersonationActive({ sessionId, impersonator, target, startedAt: new Date().toISOString(), reason }));

  // 3. Cache flush event chain
  authEpoch.bump();              // metadata-cache invalidation
  permissionProvider.refresh();   // /v1/authz/me re-fetch
  queryClient.clear();           // react-query
  zanzibarCache.clear();
  reportingMetadataCache.clear();
  companyHeaderState.reset();    // X-Company-Id NULL → target re-pick

  // 4. Multi-tab sync
  authSync.broadcast({ type: 'impersonation-started', state: ... });

  // 5. Force remount (impersonation context her route component'inde re-render)
  router.navigate(window.location.pathname, { replace: true });
}
```

Symmetric `stopImpersonation` aynı pattern.

### 3.4 Audit dashboard (PR-D, MVP sonrası)

```
/admin/audit/impersonation-logs

Filters: date range, impersonator (search), target (search), session status (active|completed|failed)
Columns: started_at, stopped_at, duration, impersonator, target, reason, action_count, ip_address

Export: CSV/Excel (compliance reporting)
```

Yeni rapor JSON: `report-service/src/main/resources/reports/audit-impersonation-logs.json`
- schemaMode: static
- source: impersonation_sessions JOIN permission_audit_events
- access.permission: IMPERSONATION_AUDIT_VIEW (SuperAdmin only by default)

## 4. Spike (impl öncesi şart)

### 4.1 Acceptance kriteri (Codex iter-2 prescription)

1. Token exchange ile target subject token üretilebiliyor mu?
2. Impersonator identity güvenilir biçimde taşınıyor mu (act claim native, custom mapper, broker session lookup)?
3. Token iss/aud/azp gateway/backend reject etmiyor mu?
4. Backend impersonation context yoksa fail-closed reddedebilecek net sinyal var mı?

### 4.2 Spike runbook

```bash
# 1. Operator: Keycloak admin console'da impersonation-broker client oluştur
#    - Client ID: impersonation-broker
#    - Client Authentication: ON
#    - Service Accounts Roles: ON
#    - Standard Flow: OFF
#    - Capability config → Token Exchange: ON

# 2. Operator: Service Accounts tab → Service Account Roles
#    Realm Roles → assign: impersonation
#    Client Roles → realm-management → assign: view-users, query-users

# 3. Operator: Permissions tab → Token Exchange permission ON
#    Audience: frontend (allowed)

# 4. Operator: Credentials tab → Client Secret kopyala → BROKER_SECRET env

# 5. Admin JWT al (mevcut admin user, browser network tab → Authorization header)
ADMIN_JWT="eyJ..."  # operator browser'dan kopyalı

# 6. Target user ID al (Keycloak admin → users → target user → ID)
TARGET_USER_ID="cbc9a869-..."  # örn. d35-admin

# 7. Token exchange request
EXCHANGED=$(curl -sk -X POST "https://testai.acik.com/realms/platform-test/protocol/openid-connect/token" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  -d "client_id=impersonation-broker" \
  -d "client_secret=$BROKER_SECRET" \
  -d "subject_token=$ADMIN_JWT" \
  -d "requested_subject=$TARGET_USER_ID" \
  -d "audience=frontend" \
  | jq -r '.access_token')

# 8. Decode + claim inspection
echo "$EXCHANGED" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{
  iss, sub, aud, azp, scope, typ, acr,
  act, impersonation_session_id, impersonator_user_id, target_user_id,
  realm_access: .realm_access.roles, resource_access
}'

# 9. Backend smoke
curl -sk -H "Authorization: Bearer $EXCHANGED" \
  https://testai.acik.com/api/v1/authz/me

curl -sk -H "Authorization: Bearer $EXCHANGED" \
  -H "X-Company-Id: 35" \
  "https://testai.acik.com/api/v1/reports/fin-muhasebe-detay/data?page=1&pageSize=5"
```

### 4.3 Spike çıktı sınıflandırması

| Sonuç | Karar |
|---|---|
| `act` claim native + admin sub içinde | ✅ Best — direct impl |
| `act` yok ama custom mapper ile deterministic | ✅ Acceptable — mapper script ekle |
| Sadece `impersonation_session_id` claim | ⚠ Acceptable with design change — backend session table lookup zorunlu |
| Hiçbir actor sinyali yok | ❌ Fail — A2 backup (custom JWT signing) ya da feature defer |

## 5. Threat model

### 5.1 Privilege escalation
- **Risk**: Admin başka admin'i impersonate edip onun super-admin grant alır
- **Mitigation**: Denylist (super-admin/sec-admin/role-admin asla impersonate edilemez); token-exchange permission policy Keycloak fine-grained; nested impersonation YASAK (act claim varsa exchange reject)

### 5.2 Token leakage
- **Risk**: Exchanged token cookie/storage'dan sızıyor
- **Mitigation**: HttpOnly cookie + SameSite=Strict; Secure flag; localStorage yerine sessionStorage (sekme kapatınca silinir); kısa expiry (15 dk, refresh off)

### 5.3 Audit completeness
- **Risk**: İmpersonation sırasında yapılan yazma operasyonları sadece target'a kayıt; gerçek aktör (impersonator) kaybolur
- **Mitigation**: act claim middleware her event'i is_impersonated=true + impersonator+target columns ile etiketler; PR'da fail-closed unit test

### 5.4 Multi-tab leak
- **Risk**: Admin sekme A'da çalışırken sekme B'de impersonation yaptı; sekme A'nın da token'ı swap oluyor (storage shared)
- **Mitigation**: Bu beklenen davranış (Codex iter-1 §4 absorb — per-tab impersonation YASAK); banner her tab'da görünür; localStorage event listener tüm tab'lar update

### 5.5 Saved preferences poisoning
- **Risk**: Impersonated session içinde admin "save grid layout" yaparsa target user'ın preference'ı admin'in tercihiyle kirlenir
- **Mitigation**: Yazma operasyonları (preferences/saved variants) impersonated mode'da blok veya impersonator user_id ile sakla (şeffaf); MVP: blok + uyarı toast

### 5.6 Background job ownership
- **Risk**: Impersonated session'da başlatılan async export, kullanıcı stop bastıktan sonra tamamlanır → mail kim'e gider, kim audit'lenir
- **Mitigation**: Background job'a session context snapshot (sessionId, impersonator, target) attach edilir; job tamamlandığında bu context ile audit; mail target'a gider (target'ın tercihi olarak başlatılan rapor); MVP: dökümante et

### 5.7 SSE/WebSocket identity drift
- **Risk**: Long-lived stream impersonation start öncesi açıldı; mid-stream identity değişimi
- **Mitigation**: Identity switch event → tüm stream'ler force-close + reconnect; reconnect sonrası effective_token kullanılır

### 5.8 MFA/step-up
- **Risk**: Admin password sızdı + impersonation kullanıldı → herhangi bir kullanıcı taklit edilebilir
- **Mitigation (MVP-)**: Audit + denylist + reason field; MFA challenge **post-MVP** (Faz 2)

## 6. PR sequence

| PR | Repo | Scope | Süre | Bağımlılık |
|---|---|---|---|---|
| **Spike** | manuel | Keycloak token-exchange smoke + claim inspection (4 acceptance) | 1-2 saat | - |
| **PR-A** | platform-k8s-gitops | Keycloak realm config (impersonation-broker client + service account roles + token-exchange permission) | ~3 gün | Spike PASS |
| **PR-B** | platform-backend | auth-service broker endpoint + permission-service Flyway V19 audit migration + 8 yeni kolon + impersonation_sessions tablosu + act claim middleware (auth-service + tüm backend) + AuditEventType extension | ~5-7 gün | PR-A PASS staging |
| **PR-C** | platform-web | mfe-shell user picker modal + global banner + auth-sync impersonation payload + cache flush event chain + Redux extension | ~4-5 gün | PR-B mergeable |
| **PR-D** | platform-backend | audit-impersonation-logs.json yeni rapor + IMPERSONATION_AUDIT_VIEW permission | ~3 gün | PR-C MVP testai LIVE |
| **GitOps digest pin** | platform-k8s-gitops | Backend image digest pin (test overlay; prod ayrı PR) | <1 gün | PR-B image build |
| **Security review** | external | Threat model + appsec review + staging abuse tests | 2-3 gün paralel | PR-B+C testai |

**Toplam**: ~3-4 sprint (15-20 iş günü), security review paralel.

## 7. Edge case checklist

- [x] Privilege escalation (denylist + nested impersonation forbid)
- [x] Token leakage (HttpOnly cookie + short expiry + sessionStorage)
- [x] Audit completeness (act claim middleware + is_impersonated columns)
- [x] Multi-tab leak (storage event sync)
- [x] Cache invalidation (event-driven, NOT TTL shortening)
- [x] X-Company-Id reset (target user scope re-pick)
- [x] Saved preferences poisoning (write blok + toast)
- [x] Background job ownership (session context snapshot)
- [x] SSE/WebSocket identity drift (force-close + reconnect)
- [x] MFA/step-up (Faz 2 — MVP'de denylist+reason)
- [x] Reason/ticket required (min 10 char)
- [x] PII log redaction (audit dashboard email/phone partial mask)
- [x] Keycloak realm drift test/prod parity (GitOps manifest)
- [x] Original token expiry (Keycloak silent SSO refresh)
- [x] Public client token-exchange abuse (broker confidential)
- [x] act claim missing (fail-closed reject)
- [x] Single-active-session policy (409 strict MVP)
- [x] User disable/notBefore/revocation (target enabled check pre-exchange)
- [x] Non-Workcube users (whitelist sadece sistemde onboard user)
- [x] Workcube ERP-side parallel session (out of scope, dökümante)
- [x] CompanyHeaderScopeNarrower behavior (target user scope'una göre 403/preserve)

## 8. Acceptance criteria (Definition of Done)

### MVP DoD

1. ✅ Spike PASS (act claim veya broker session lookup + audience validator OK)
2. ✅ PR-A merged + staging Keycloak'ta impersonation-broker client LIVE
3. ✅ PR-B merged + Flyway V19 migration applied + audit middleware fail-closed test
4. ✅ PR-C merged + mfe-shell user picker + banner + cache flush LIVE testai
5. ✅ Live smoke: SuperAdmin → impersonate non-admin user → /admin/access/roles drawer'lar target'ın yetki sınırına göre değişir → stop → orijinal admin
6. ✅ Audit log entry: IMPERSONATION_STARTED + impersonated REPORT_ACCESS + IMPERSONATION_STOPPED 3 event PG'de
7. ✅ Multi-tab smoke: 2 sekme açık, sekme A impersonate başlat, sekme B'de banner görünür, sekme B stop bastığında ikisinde de durur
8. ✅ Edge case unit test coverage: nested impersonation forbid, denylist hit, reason required, target disabled
9. ✅ Codex iter cycle AGREE
10. ✅ Security review PASS

### Post-MVP (Faz 2)

- Audit dashboard rapor sayfası
- Granular admin policy (Admin sadece kendi şirket scope'undaki user)
- MFA/step-up authentication
- Cross-tenant impersonation (security review + product spec)

## 9. Riskler ve mitigasyon

| Risk | Olasılık | Etki | Mitigasyon |
|---|---|---|---|
| Spike fail (act claim native yok) | Orta | Orta | Custom mapper + broker session lookup backup; Keycloak 26.x feature gap araştır |
| Frontend cache flush event chain zorluğu | Düşük | Orta | mfe-shell mevcut auth-sync pattern reuse; per-mfe metadata-cache zaten clearCache() API'si var |
| Multi-tab race condition | Düşük | Düşük | BroadcastChannel + storage event idempotent state machine |
| Audit migration prod data drift | Düşük | Yüksek | Flyway additive nullable + index CONCURRENTLY (CONCURRENTLY index PG-only, MSSQL'de ONLINE=ON) |
| Workcube ERP integration | Düşük | Düşük | Out of scope; paralel session bağımsız |
| Security review uzun sürmesi | Yüksek | Orta | PR-B+C testai LIVE iken paralel başlat |

## 10. Referanslar

- **Codex thread**: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
  - iter-1 REVISE → A2 backend-brokered (frontend public client'a açma yasak)
  - iter-2 AGREE ready_for_spike: true
- **Önceki PR'lar**:
  - 6 finansal rapor SETUP_PROCESS_CAT JOIN fix (Codex `019e0c99` 6-iter)
  - satis-ozet/stok-durum schemaMode=current per-tenant resolver (Codex `019e0d06` 4-iter, PR #133 LIVE)
- **Spec ortakları**:
  - `docs/plans/2026-05-reporting-phase-2-program-2-runtime-tenant-guard-spec.md`
  - `docs/adr/0011-governance-layer.md`
- **External**:
  - RFC 8693 OAuth 2.0 Token Exchange
  - Keycloak Server Administration: Token Exchange section
  - OWASP Authentication Cheat Sheet — Impersonation

## 11. Changelog

| Tarih | İter | Değişim |
|---|---|---|
| 2026-05-09 | iter-1 | Initial draft, Codex 019e0dfb iter-1 REVISE absorb (A → A2 pivot) |
| 2026-05-09 | iter-2 | Codex iter-2 AGREE absorb (spike-first + multi-PR sequence + edge cases extended) |
| 2026-05-09 | iter-3 | Spike-1 koşuldu: feature/client/auth OK, **fine-grained authz policy default-deny** Spike-1 stop point. PR-A scope revize: tek atomic PR (broker client + policy + verify + smoke runbook + Vault secret reference). Spike-2 PR-A apply sonrası operator manuel; PR-B gate `PASS_NATIVE_ACT` veya `PASS_JTI_SESSION_LOOKUP`. Spike artifact: [docs/spikes/2026-05-impersonation-token-exchange-spike.md](../spikes/2026-05-impersonation-token-exchange-spike.md). `act` yoksa **`jti` + broker session lookup** primary fallback (Codex iter-3 §3 — custom JWT signing'den daha pragmatik). |
