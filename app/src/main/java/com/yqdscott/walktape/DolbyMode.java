package com.yqdscott.walktape;

/** User-selectable noise-reduction position on machines fitted for Dolby B/C. */
public enum DolbyMode {
    OFF("off", "OFF", 0),
    B("b", "B", 10),
    C("c", "C", 20);

    public final String id;
    public final String label;
    public final int maximumNoiseReductionDb;

    DolbyMode(String id, String label, int maximumNoiseReductionDb) {
        this.id = id;
        this.label = label;
        this.maximumNoiseReductionDb = maximumNoiseReductionDb;
    }

    public static DolbyMode forId(String id) {
        if (B.id.equals(id)) {
            return B;
        }
        if (C.id.equals(id)) {
            return C;
        }
        return OFF;
    }

    public static DolbyMode forSelectorIndex(int index) {
        if (index == 1) {
            return B;
        }
        if (index == 2) {
            return C;
        }
        return OFF;
    }

    public DolbyMode next() {
        return this == OFF ? B : this == B ? C : OFF;
    }
}
