package com.sstlfsj.fibra.internal;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

final class IdentityList<T> {
    private final List<T> values = new ArrayList<>();
    private final IdentityHashMap<T, Boolean> identities = new IdentityHashMap<>();

    public void add(T value) {
        if (identities.put(value, Boolean.TRUE) == null) {
            values.add(value);
        }
    }

    public boolean remove(T value) {
        if (identities.remove(value) == null) {
            return false;
        }
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == value) {
                values.remove(index);
                return true;
            }
        }
        return false;
    }

    public List<T> snapshot() {
        return List.copyOf(values);
    }

    public List<T> drainReverse() {
        var result = new ArrayList<T>(values.size());
        for (int index = values.size() - 1; index >= 0; index--) {
            result.add(values.get(index));
        }
        values.clear();
        identities.clear();
        return result;
    }
}
