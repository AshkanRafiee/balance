package com.ashkanrafiee.balance.parser.catalog;

import java.util.Map;
import java.util.Objects;

/** Explicit, bounded resolution of sender collisions. The compiler refuses a
 * normalized sender owned by two different banks unless a pin names the single
 * winner; a pin that does not match a real collision, or that rewrites the
 * existing winner, is rejected. The legacy winners are pinned as declared
 * compatibility data, so the allowlist cannot grow silently. */
public final class CollisionAllowlist {
    public static final CollisionAllowlist NONE = new CollisionAllowlist(Map.of());
    /** The three frozen Iranian collisions, named by their normalized key and the
     * first-declared winner preserved by the legacy contract. */
    public static final CollisionAllowlist IR_LEGACY_S1 = new CollisionAllowlist(Map.of(
        "20004860", "ir.middle-east",
        "30005816", "ir.tosee-taavon",
        "98700717", "ir.melli"));

    private final Map<String, String> pins;

    private CollisionAllowlist(Map<String, String> pins) {
        this.pins = Map.copyOf(pins);
        for (Map.Entry<String, String> pin : this.pins.entrySet()) {
            Objects.requireNonNull(pin.getKey());
            Objects.requireNonNull(pin.getValue());
            if (pin.getKey().isEmpty() || pin.getValue().isEmpty())
                throw new IllegalArgumentException("empty collision pin");
        }
    }

    public boolean isEmpty() { return pins.isEmpty(); }

    public Map<String, String> pins() { return pins; }

    public String winner(String key) { return pins.get(key); }

    /** Explicit, immutable pins keyed by normalized sender. Only the declared
     * IR_LEGACY_S1 set is accepted for the Iranian port; this factory exists for
     * scoped local packs that document their own collisions. */
    public static CollisionAllowlist of(Map<String, String> pins) {
        return new CollisionAllowlist(pins);
    }
}