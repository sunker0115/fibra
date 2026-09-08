package com.sstlfsj.fibra.runtime.java;

import java.util.Objects;
import java.util.regex.Pattern;

record Version(int major, int minor, int patch, String preRelease)
    implements Comparable<Version> {
    private static final Pattern PATTERN = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version numbers must not be negative");
        }
    }

    static Version parse(String value) {
        Objects.requireNonNull(value, "version");
        var matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid semantic version " + value);
        }
        var preRelease = matcher.group(4);
        if (preRelease != null) {
            for (var identifier : preRelease.split("\\.")) {
                if (numeric(identifier) && identifier.length() > 1
                    && identifier.charAt(0) == '0') {
                    throw new IllegalArgumentException(
                        "numeric pre-release identifier has a leading zero in " + value);
                }
            }
        }
        return new Version(Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
            preRelease);
    }

    @Override
    public int compareTo(Version other) {
        var compared = Integer.compare(major, other.major);
        if (compared == 0) {
            compared = Integer.compare(minor, other.minor);
        }
        if (compared == 0) {
            compared = Integer.compare(patch, other.patch);
        }
        if (compared != 0) {
            return compared;
        }
        if (preRelease == null) {
            return other.preRelease == null ? 0 : 1;
        }
        if (other.preRelease == null) {
            return -1;
        }
        var left = preRelease.split("\\.");
        var right = other.preRelease.split("\\.");
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            var identifier = compareIdentifier(left[index], right[index]);
            if (identifier != 0) {
                return identifier;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static int compareIdentifier(String left, String right) {
        var leftNumeric = numeric(left);
        var rightNumeric = numeric(right);
        if (leftNumeric && rightNumeric) {
            var length = Integer.compare(left.length(), right.length());
            return length == 0 ? left.compareTo(right) : length;
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static boolean numeric(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
