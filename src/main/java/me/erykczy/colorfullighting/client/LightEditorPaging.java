package me.erykczy.colorfullighting.client;

import java.util.ArrayList;
import java.util.List;

/** Keeps configured and unconfigured results on wholly separate pages. */
public final class LightEditorPaging {
    private LightEditorPaging() {
    }

    public static <T> List<Segment<T>> partition(
            List<T> configured,
            List<T> unconfigured,
            int pageSize
    ) {
        if(pageSize < 1) throw new IllegalArgumentException("Page size must be positive");
        ArrayList<Segment<T>> result = new ArrayList<>();
        append(result, configured, true, pageSize);
        append(result, unconfigured, false, pageSize);
        return List.copyOf(result);
    }

    private static <T> void append(
            List<Segment<T>> sink,
            List<T> source,
            boolean configured,
            int pageSize
    ) {
        for(int start = 0; start < source.size(); start += pageSize) {
            sink.add(new Segment<>(
                    List.copyOf(source.subList(start, Math.min(source.size(), start + pageSize))),
                    configured
            ));
        }
    }

    public record Segment<T>(List<T> entries, boolean configured) {
    }
}
