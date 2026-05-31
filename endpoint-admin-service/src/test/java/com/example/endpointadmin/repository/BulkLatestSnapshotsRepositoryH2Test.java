package com.example.endpointadmin.repository;

import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;

/**
 * BE — H2 tier of the #1146 bulk latest-snapshots behaviour
 * (see {@link AbstractBulkLatestSnapshotsRepositoryTest}). H2 runs in
 * {@code MODE=PostgreSQL}; this tier proves the window function + LIMIT
 * binding compile and behave on the fast in-memory engine every PR runs.
 */
@IsolatedH2DataJpaTest
class BulkLatestSnapshotsRepositoryH2Test extends AbstractBulkLatestSnapshotsRepositoryTest {
}
