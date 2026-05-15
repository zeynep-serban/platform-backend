package com.example.report.contract.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R16 PR-C — Source-side {@link AuthzReferenceRegistry} implementation.
 *
 * <p>Canonical OpenFGA model dosyasından ({@code backend/openfga/model.fga})
 * type tanımlarını parse eder. {@code type report_group}, {@code type report},
 * {@code type module}, vb. — bu types üzerinden instance key'leri (örn.
 * {@code report_group:FINANCE_REPORTS}) WARN-first contract gate'ine girer.
 *
 * <p><b>Sınırlama (PR-C kapsamı)</b>: OpenFGA DSL type'ları obje id envanteri
 * taşımaz (Codex 019e27f5 önerisi). Bu yüzden registry sadece:
 * <ul>
 *   <li>type X tanımı var mı (örn. {@code type report_group} eklenmiş mi)?</li>
 * </ul>
 * Spesifik {@code report_group:FINANCE_REPORTS} INSTANCE varlığı runtime
 * tuple seed sorumluluğundadır (PR-B-2 kapsamı). RC-012 PR-C kapsamında
 * SADECE type-level kontrol yapar.
 *
 * <p>Source path resolution: working directory'den canonical {@code backend/openfga/model.fga}
 * okunur. Maven module root'tan koşulduğunda relative path:
 * {@code ../backend/openfga/model.fga}. Test'ten koşulduğunda explicit override.
 */
public final class OpenFgaModelAuthzReferenceRegistry implements AuthzReferenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(
            OpenFgaModelAuthzReferenceRegistry.class);

    private static final Pattern TYPE_PATTERN = Pattern.compile("^type\\s+(\\w+)\\s*$",
            Pattern.MULTILINE);

    /**
     * Codex 019e27f5 PR-C REVISE P1 absorb: PermissionDataInitializer source
     * parse — transitional pattern (Codex önerisi: "source parser PermissionDataInitializer
     * üstünden yapılabilir, ama bunu transitional kabul et").
     *
     * <p>Matches: {@code "reports.HR_REPORTS"}, {@code "reports.FINANCE_REPORTS"}, etc.
     */
    private static final Pattern PERMISSION_REPORT_GROUP_PATTERN = Pattern.compile(
            "\"reports\\.([A-Z_]+)\"");

    private static final Path DEFAULT_PERMISSION_INITIALIZER_PATH = Path.of(
            "../permission-service/src/main/java/com/example/permission/config/PermissionDataInitializer.java");

    private final Path canonicalModelPath;
    private final Path permissionInitializerPath;
    private final Set<String> reportGroups;
    private final Set<String> permissions;

    /**
     * Default constructor — canonical model `../backend/openfga/model.fga` +
     * source parse `../permission-service/.../PermissionDataInitializer.java`
     * (Maven module root'tan; transitional pattern Codex 019e27f5 P1 absorb).
     */
    public OpenFgaModelAuthzReferenceRegistry() {
        this(Path.of("../backend/openfga/model.fga"), DEFAULT_PERMISSION_INITIALIZER_PATH);
    }

    public OpenFgaModelAuthzReferenceRegistry(Path canonicalModelPath) {
        this(canonicalModelPath, DEFAULT_PERMISSION_INITIALIZER_PATH);
    }

    public OpenFgaModelAuthzReferenceRegistry(Path canonicalModelPath,
                                              Path permissionInitializerPath) {
        this.canonicalModelPath = canonicalModelPath;
        this.permissionInitializerPath = permissionInitializerPath;
        this.reportGroups = computeReportGroups();
        this.permissions = Collections.emptySet(); // PR-C kapsamı dışı (PR-C-2)
    }

    @Override
    public Set<String> knownReportGroups() {
        return reportGroups;
    }

    @Override
    public Set<String> knownPermissions() {
        return permissions;
    }

    /**
     * Canonical model dosyasında {@code type report_group} tanımı varsa,
     * R16 PR-B sonrası report_group authz contract aktiftir.
     *
     * @return type report_group present in canonical model
     */
    public boolean isReportGroupTypeRegistered() {
        return knownTypes().contains("report_group");
    }

    /**
     * Tüm OpenFGA type tanımlarını canonical model'den parse et.
     */
    public Set<String> knownTypes() {
        if (!Files.exists(canonicalModelPath)) {
            log.warn("Canonical OpenFGA model dosyası bulunamadı: {}", canonicalModelPath);
            return Collections.emptySet();
        }
        try {
            String content = Files.readString(canonicalModelPath);
            Matcher m = TYPE_PATTERN.matcher(content);
            Set<String> types = new HashSet<>();
            while (m.find()) {
                types.add(m.group(1));
            }
            return Collections.unmodifiableSet(types);
        } catch (IOException ex) {
            log.warn("Canonical OpenFGA model okuma hatası: {}", ex.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * PermissionDataInitializer source'dan `reports.<GROUP>` key set'i parse.
     *
     * <p>Codex 019e27f5 PR-C REVISE P1 absorb: actual reportGroup registry
     * implementation. Transitional pattern (Codex: "kısa vadede source parser
     * PermissionDataInitializer üstünden yapılabilir, ama bunu transitional
     * kabul et").
     *
     * <p>Daha kalıcı çözüm: ortak `report-groups.yaml` shared resource
     * (PR-C-2'de). Şu an permission-service ana source'tur.
     */
    private Set<String> computeReportGroups() {
        // 1. Canonical model'de type report_group yoksa registry kapalı.
        if (!isReportGroupTypeRegistered()) {
            return Collections.emptySet();
        }
        // 2. PermissionDataInitializer source'dan reports.<GROUP> key'leri parse.
        if (!Files.exists(permissionInitializerPath)) {
            log.warn("PermissionDataInitializer source bulunamadı: {} — reportGroup"
                    + " registry boş, RC-012 type-level WARN'a düşer",
                    permissionInitializerPath);
            return Collections.emptySet();
        }
        try {
            String content = Files.readString(permissionInitializerPath);
            Matcher m = PERMISSION_REPORT_GROUP_PATTERN.matcher(content);
            Set<String> groups = new HashSet<>();
            while (m.find()) {
                groups.add(m.group(1));
            }
            log.debug("Parsed {} reportGroups from PermissionDataInitializer", groups.size());
            return Collections.unmodifiableSet(groups);
        } catch (IOException ex) {
            log.warn("PermissionDataInitializer parse hatası: {}", ex.getMessage());
            return Collections.emptySet();
        }
    }
}
