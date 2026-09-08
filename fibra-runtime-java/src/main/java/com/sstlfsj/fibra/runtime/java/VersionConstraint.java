package com.sstlfsj.fibra.runtime.java;

import java.util.function.Predicate;

final class VersionConstraint {
    private final String expression;
    private final Predicate<Version> predicate;

    private VersionConstraint(String expression, Predicate<Version> predicate) {
        this.expression = expression;
        this.predicate = predicate;
    }

    static VersionConstraint parse(String value) {
        var expression = value.trim();
        if (expression.equals("*")) {
            return new VersionConstraint(expression, ignored -> true);
        }
        if (expression.startsWith("^")) {
            var floor = Version.parse(expression.substring(1));
            var ceiling = floor.major() > 0
                ? new Version(floor.major() + 1, 0, 0, null)
                : floor.minor() > 0
                    ? new Version(0, floor.minor() + 1, 0, null)
                    : new Version(0, 0, floor.patch() + 1, null);
            return range(expression, floor, ceiling);
        }
        if (expression.startsWith("~")) {
            var floor = Version.parse(expression.substring(1));
            return range(expression, floor,
                new Version(floor.major(), floor.minor() + 1, 0, null));
        }
        if (expression.contains(" ")) {
            Predicate<Version> result = ignored -> true;
            for (var part : expression.split("\\s+")) {
                result = result.and(comparator(part));
            }
            return new VersionConstraint(expression, result);
        }
        var exact = Version.parse(expression);
        return new VersionConstraint(expression, exact::equals);
    }

    boolean matches(String version) {
        return predicate.test(Version.parse(version));
    }

    private static VersionConstraint range(String expression, Version floor,
                                           Version ceiling) {
        return new VersionConstraint(expression,
            value -> value.compareTo(floor) >= 0 && value.compareTo(ceiling) < 0);
    }

    private static Predicate<Version> comparator(String expression) {
        if (expression.startsWith(">=")) {
            var value = Version.parse(expression.substring(2));
            return candidate -> candidate.compareTo(value) >= 0;
        }
        if (expression.startsWith("<=")) {
            var value = Version.parse(expression.substring(2));
            return candidate -> candidate.compareTo(value) <= 0;
        }
        if (expression.startsWith(">")) {
            var value = Version.parse(expression.substring(1));
            return candidate -> candidate.compareTo(value) > 0;
        }
        if (expression.startsWith("<")) {
            var value = Version.parse(expression.substring(1));
            return candidate -> candidate.compareTo(value) < 0;
        }
        var value = Version.parse(expression);
        return value::equals;
    }

    @Override
    public String toString() {
        return expression;
    }
}
