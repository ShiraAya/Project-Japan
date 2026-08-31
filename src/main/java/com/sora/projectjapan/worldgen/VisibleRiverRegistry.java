package com.sora.projectjapan.worldgen;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFIX-19 single identity authority for visible runtime RiverCourse ownership.
 *
 * <p>The frozen 115 authored rivers and future P0/P1/P2 imports use the same W05 identifier
 * lookup. Future import code must register the official W05 identifier before connector state is
 * materialized. The audit-only overload accepts synthetic owners without mutating runtime state.</p>
 */
final class VisibleRiverRegistry {
    private static final Set<String> FROZEN_AUTHORED_OWNERS = buildFrozenAuthoredOwners();
    private static final Set<String> IMPORTED_VISIBLE_OWNERS = ConcurrentHashMap.newKeySet();

    private VisibleRiverRegistry() {}

    static boolean hasVisibleOwner(String officialIdentifier) {
        return hasVisibleOwner(officialIdentifier, Set.of());
    }

    static boolean hasVisibleOwner(String officialIdentifier, Collection<String> syntheticOwners) {
        if (officialIdentifier == null || officialIdentifier.isEmpty()) return false;
        return FROZEN_AUTHORED_OWNERS.contains(officialIdentifier)
                || IMPORTED_VISIBLE_OWNERS.contains(officialIdentifier)
                || (syntheticOwners != null && syntheticOwners.contains(officialIdentifier));
    }

    /**
     * P0/P1/P2 import hook. Registration changes identity ownership only; it does not create
     * geometry and therefore must happen as part of visible RiverCourse construction/import.
     */
    static void registerImportedVisibleOwner(String officialIdentifier) {
        if (officialIdentifier == null || !officialIdentifier.startsWith("W05:")) {
            throw new IllegalArgumentException("Visible river owner must use an official W05 identifier: "
                    + officialIdentifier);
        }
        IMPORTED_VISIBLE_OWNERS.add(officialIdentifier);
    }

    static int frozenAuthoredOwnerCount() { return FROZEN_AUTHORED_OWNERS.size(); }
    static int importedVisibleOwnerCount() { return IMPORTED_VISIBLE_OWNERS.size(); }
    static Set<String> frozenAuthoredOwnersForAudit() { return FROZEN_AUTHORED_OWNERS; }

    private static Set<String> buildFrozenAuthoredOwners() {
        Set<String> ids = new HashSet<>();
        for (W05AuthoredReference.Route route : W05AuthoredReference.routesForAudit().values()) {
            if (route.officialIdentifier() == null || route.officialIdentifier().isEmpty()) {
                throw new IllegalStateException("Authored visible river is missing W05 identity");
            }
            if (!ids.add(route.officialIdentifier())) {
                throw new IllegalStateException("Duplicate authored visible W05 identity: "
                        + route.officialIdentifier());
            }
        }
        return Set.copyOf(ids);
    }
}
