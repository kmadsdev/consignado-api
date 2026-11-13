package io.github.kmadsdev.consignado.api;

/**
 * The contract's JPA {@code @Version} doubles as its HTTP entity tag. A client sends it
 * back in If-Match on a state change; a stale value gets 412 instead of overwriting.
 */
final class ETags {

    private ETags() {
    }

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Returns null for a missing header or "*" (no precondition). */
    static Long parseIfMatch(String header) {
        if (header == null || header.isBlank() || header.strip().equals("*")) {
            return null;
        }
        String value = header.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // A tag we never issued cannot match the current version.
            return -1L;
        }
    }
}
