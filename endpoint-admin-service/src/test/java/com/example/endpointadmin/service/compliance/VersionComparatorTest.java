package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.CatalogVersionPolicyType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the BE-023 version comparator backed by
 * {@link org.apache.maven.artifact.versioning.ComparableVersion}.
 */
class VersionComparatorTest {

    @Test
    void latestPolicyAlwaysSatisfiesEvenWithoutInstalledVersion() {
        assertThat(VersionComparator.compare(CatalogVersionPolicyType.LATEST, null, null))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void exactMatchSatisfies() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.EXACT, "24.07", "24.07"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void exactMismatchViolates() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.EXACT, "24.07", "23.01"))
                .isEqualTo(VersionComparator.Result.VIOLATES);
    }

    @Test
    void minimumLowerInstalledViolates() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.MINIMUM, "24.07", "23.01"))
                .isEqualTo(VersionComparator.Result.VIOLATES);
    }

    @Test
    void minimumEqualOrHigherSatisfies() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.MINIMUM, "24.07", "24.07"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.MINIMUM, "24.07", "25.00"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void rangeInclusiveBothBoundsRespected() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,25.0]", "24.5"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,25.0]", "24.0"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,25.0]", "25.0"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void rangeExclusiveUpperRejectsBoundary() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,25.0)", "25.0"))
                .isEqualTo(VersionComparator.Result.VIOLATES);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,25.0)", "24.99"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void rangeUnboundedUpper() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[24.0,)", "200.0"))
                .isEqualTo(VersionComparator.Result.SATISFIES);
    }

    @Test
    void blankInstalledIsUnsupported() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.EXACT, "24.07", ""))
                .isEqualTo(VersionComparator.Result.UNSUPPORTED);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.EXACT, "24.07", null))
                .isEqualTo(VersionComparator.Result.UNSUPPORTED);
    }

    @Test
    void blankPolicyValueIsUnsupportedForEXACT() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.EXACT, null, "24.07"))
                .isEqualTo(VersionComparator.Result.UNSUPPORTED);
    }

    @Test
    void malformedRangeIsUnsupported() {
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "garbage", "24.07"))
                .isEqualTo(VersionComparator.Result.UNSUPPORTED);
        assertThat(VersionComparator.compare(
                CatalogVersionPolicyType.RANGE, "[a", "24.07"))
                .isEqualTo(VersionComparator.Result.UNSUPPORTED);
    }
}
