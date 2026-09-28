package com.ashkanrafiee.balance.parser.identity;

import java.util.Objects;

final class Names {
    private Names() {}

    // Public names and opaque IDs only; never echo rejected input in diagnostics.
    static String require(String value) {
        Objects.requireNonNull(value, "name");
        if (value.length() < 1 || value.length() > 128) {
            throw new IllegalArgumentException("Invalid identity name length");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') {
                throw new IllegalArgumentException("Invalid identity name");
            }
        }
        return value;
    }
}
