package com.example.report.contract.registry;

import java.util.Set;

/**
 * R16 PR-C — Authz reference registry (Codex 019e27f5 PARTIAL absorb Option 2).
 *
 * <p>{@link com.example.report.registry.ReportDefinition} içinde belirtilen authz
 * referansları (reportGroup, permission key, action scope) authz plane'de
 * (OpenFGA model, permission catalog) gerçekten tanımlı mı kontrol için
 * source-of-truth abstraction.
 *
 * <p>İlk faz (PR-C): WARN-first. Eksik referanslar WARN log üretir ama CI fail
 * etmez. {@code authz-reference-debt.yaml} ile explicit deferral mekanizması;
 * {@code warn_until} tarihi geçince RC-012 WARN → FAIL'e döner.
 *
 * <p>Codex 019e27f5 PR-C önerisi: "RC-012 source contract gate olmalı. Runtime
 * API check ayrı doctor/live drift gate olabilir." Dolayısıyla bu registry
 * source-side parser kullanır (model.fga + permission catalog seed dosyaları);
 * runtime OpenFGA API çağrısı YAPMAZ.
 *
 * <p>R16 close-out discipline guard'ı: R15'in tekrar yaşanmamasını sağlar —
 * yeni reportGroup veya authz reference eklenirken model + catalog senkronize
 * mı kontrol edilir.
 *
 * @see com.example.report.contract.rules.RC012AuthzReferenceCheck
 * @see ADR-0017 Contract-Registry Cross-Check Pattern
 */
public interface AuthzReferenceRegistry {

    /**
     * Mevcut report_group anahtarları (canonical OpenFGA model'de tanımlı +
     * permission catalog'da grant'lenmiş). Source-side parse sonucu döner.
     */
    Set<String> knownReportGroups();

    /**
     * Mevcut permission anahtarları (permission-service catalog seed'de
     * tanımlı). Örn: "REPORT_VIEW", "REPORT_EXPORT", "reports.FINANCE_REPORTS".
     */
    Set<String> knownPermissions();

    /**
     * Bu registry'nin warn_until expiry geçmiş debt entry'leri var mı?
     * Test harness'i için (RC-012 expiry enforcement).
     */
    default boolean hasExpiredDebt() {
        return false;
    }
}
