package me.erykczy.colorfullighting.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** One-pass identity grouping used for Block.asItem families. */
public final class IdentityFamilyGrouping {
    private IdentityFamilyGrouping() {
    }

    public static <K, V> IdentityHashMap<K, List<V>> group(
            Iterable<V> values,
            Function<V, K> keyFunction,
            Predicate<K> includedKey
    ) {
        IdentityHashMap<K, List<V>> result = new IdentityHashMap<>();
        for(V value : values) {
            K key = keyFunction.apply(value);
            if(includedKey.test(key))
                result.computeIfAbsent(key, unused -> new ArrayList<>()).add(value);
        }
        return result;
    }
}
