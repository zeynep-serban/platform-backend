package com.example.user.repository;

import com.example.user.model.User;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable JPA {@link Specification}s for {@link User} query surfaces.
 *
 * <p>Soft-delete (Codex thread {@code 019ea573}, platform-web #770 Phase 2):
 * there is deliberately NO global Hibernate {@code @Where}/{@code @SQLRestriction}
 * on the entity — the identity-resolution security paths must read tombstones
 * explicitly so a deleted user gets a clean {@code 403 USER_DELETED} and is
 * never silently resurrected. Public/query surfaces (list / group / pivot /
 * aggregation) instead exclude tombstones with {@link #notDeleted()}.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * Excludes soft-deleted tombstones — {@code deleted_at IS NULL}. Seeded
     * into {@code UserService#buildSpecification} and AND-ed into the SSRM
     * aggregation/pivot criteria so no query surface ever returns a deleted
     * user.
     */
    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }
}
