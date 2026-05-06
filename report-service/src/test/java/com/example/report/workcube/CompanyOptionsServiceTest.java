package com.example.report.workcube;

import com.example.commonauth.scope.ScopeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CompanyOptionsService} authorization filter.
 *
 * <p>Codex 019dfb15 iter-2 absorb #5: covers the three persona buckets
 * (super-admin / scoped / anonymous) plus the empty-scope edge case.
 *
 * <p>Cache wiring is covered separately in
 * {@link CompanyOptionsRepositoryCachingIT} so this test stays a pure
 * unit test (no Spring context boot).
 */
@ExtendWith(MockitoExtension.class)
class CompanyOptionsServiceTest {

    @Mock CompanyOptionsRepository repository;

    private CompanyOptionsService service;

    private static final List<CompanyOptionsRepository.CompanyOption> CATALOG = List.of(
            new CompanyOptionsRepository.CompanyOption(1, "ARC", "ARÇELİK A.Ş."),
            new CompanyOptionsRepository.CompanyOption(2, "BSH", "BSH EV ALETLERİ"),
            new CompanyOptionsRepository.CompanyOption(7, "VST", "VESTEL A.Ş.")
    );

    @BeforeEach
    void setUp() {
        service = new CompanyOptionsService(repository);
    }

    @Test
    void nullScope_returnsEmpty_evenIfRepositoryHasData() {
        // No stub on repository — should not be hit.
        List<CompanyOptionsRepository.CompanyOption> result = service.findAuthorized(null);
        assertTrue(result.isEmpty(), "null scope must short-circuit before repository call");
    }

    @Test
    void superAdmin_returnsFullCatalog() {
        when(repository.findAll()).thenReturn(CATALOG);
        ScopeContext scope = ScopeContext.superAdmin("admin@example.com");

        List<CompanyOptionsRepository.CompanyOption> result = service.findAuthorized(scope);

        assertEquals(3, result.size());
        assertEquals(CATALOG, result);
    }

    @Test
    void scopedUser_filtersToAllowedCompanyIds() {
        when(repository.findAll()).thenReturn(CATALOG);
        ScopeContext scope = new ScopeContext(
                "user@example.com",
                Set.of(1L, 7L),       // ARC + VST allowed; BSH (2) excluded
                Set.of(),
                Set.of(),
                false
        );

        List<CompanyOptionsRepository.CompanyOption> result = service.findAuthorized(scope);

        assertEquals(2, result.size());
        assertEquals(List.of(
                new CompanyOptionsRepository.CompanyOption(1, "ARC", "ARÇELİK A.Ş."),
                new CompanyOptionsRepository.CompanyOption(7, "VST", "VESTEL A.Ş.")
        ), result);
    }

    @Test
    void scopedUser_emptyAllowedIds_returnsEmpty() {
        // Catalog still loaded (cache stays warm) but user sees nothing.
        when(repository.findAll()).thenReturn(CATALOG);
        ScopeContext scope = ScopeContext.empty("user@example.com");

        List<CompanyOptionsRepository.CompanyOption> result = service.findAuthorized(scope);

        assertTrue(result.isEmpty());
    }

    @Test
    void scopedUser_idNotInCatalog_returnsEmpty() {
        // Defensive: scope grants company id 999 but it isn't in the catalog
        // (e.g. schema removed or out of allowlist range). Filter should
        // return empty, not throw.
        when(repository.findAll()).thenReturn(CATALOG);
        ScopeContext scope = new ScopeContext(
                "user@example.com",
                Set.of(999L),
                Set.of(), Set.of(), Set.of(),
                false
        );

        List<CompanyOptionsRepository.CompanyOption> result = service.findAuthorized(scope);

        assertTrue(result.isEmpty());
    }
}
