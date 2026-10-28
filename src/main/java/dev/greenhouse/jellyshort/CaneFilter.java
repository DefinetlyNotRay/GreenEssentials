package dev.greenhouse.jellyshort;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

public final class CaneFilter {
    public static Set<Integer> select(int anchorY, int height, boolean hideAll, IntPredicate isCane) {
        return select(anchorY, height, hideAll, isCane, y -> false);
    }

    public static Set<Integer> select(int anchorY, int height, boolean hideAll, IntPredicate isCane, IntPredicate isStem) {
        Set<Integer> result = new HashSet<>();
        int maxY = anchorY + 16;
        int base = anchorY - 4;
        while (base <= maxY && !isCane.test(base) && !isStem.test(base)) base++;
        for (int y = base; y <= maxY && isCane.test(y); y++) {
            if (hideAll || y - base >= height) result.add(y);
        }
        for (int y = base; y <= maxY; y++) {
            if (isStem.test(y) && (hideAll || y - base >= height)) result.add(y);
        }
        return result;
    }
}
