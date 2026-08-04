package com.superintendent.sablescopedlinks.scope;

import java.util.Objects;

public final class LinkScope {
    private final Object dimensionKey;
    private final Object subLevelKey;
    private final boolean apiResolved;

    private LinkScope(Object dimensionKey, Object subLevelKey, boolean apiResolved) {
        this.dimensionKey = dimensionKey;
        this.subLevelKey = subLevelKey;
        this.apiResolved = apiResolved;
    }

    public static LinkScope world(Object dimensionKey, boolean apiResolved) {
        return new LinkScope(dimensionKey, null, apiResolved);
    }

    public static LinkScope subLevel(Object dimensionKey, Object subLevelKey) {
        return new LinkScope(dimensionKey, subLevelKey, true);
    }

    public boolean apiResolved() {
        return apiResolved;
    }

    public boolean isWorldScope() {
        return subLevelKey == null;
    }

    public boolean sameScope(LinkScope other) {
        return Objects.equals(dimensionKey, other.dimensionKey)
                && Objects.equals(subLevelKey, other.subLevelKey);
    }

    public boolean sameDimension(LinkScope other) {
        return Objects.equals(dimensionKey, other.dimensionKey);
    }

    @Override
    public String toString() {
        return "LinkScope[dimension=" + dimensionKey
                + ", subLevel=" + subLevelKey
                + ", apiResolved=" + apiResolved + "]";
    }
}
