package com.ashkanrafiee.balance.parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Null-rejecting defensive copies using APIs available on Android 26, without library desugaring. */
final class ImmutableCollections {
    private ImmutableCollections() {}

    static <T> List<T> copyList(Collection<? extends T> source) {
        List<T> copy = new ArrayList<>(source.size());
        for (T value : source) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    static <T> List<T> listOf(T... values) {
        List<T> copy = new ArrayList<>(values.length);
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    static <T> Set<T> copySet(Collection<? extends T> source) {
        Set<T> copy = new LinkedHashSet<>();
        for (T value : source) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(copy);
    }

    static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> source) {
        Map<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : source.entrySet())
            copy.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }

    static <T> List<T> sortedList(Collection<? extends T> source, Comparator<? super T> comparator) {
        List<T> copy = new ArrayList<>(source.size());
        for (T value : source) copy.add(Objects.requireNonNull(value));
        copy.sort(comparator);
        return Collections.unmodifiableList(copy);
    }
}
