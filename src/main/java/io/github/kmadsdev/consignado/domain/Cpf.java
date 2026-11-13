package io.github.kmadsdev.consignado.domain;

import java.util.Objects;

/**
 * Brazilian individual taxpayer number (CPF), validated by its two check digits.
 *
 * <p>{@link #toString()} returns a masked value on purpose, so a CPF that ends up in a
 * log line or an exception message by accident does not leak in full. Use
 * {@link #value()} only where the full number is really needed (persistence).
 */
public final class Cpf {

    private static final int LENGTH = 11;

    private final String digits;

    private Cpf(String digits) {
        this.digits = digits;
    }

    /** Accepts "12345678909" or "123.456.789-09". */
    public static Cpf of(String raw) {
        String digits = normalize(raw);
        if (!hasValidCheckDigits(digits)) {
            throw new BusinessRuleViolationException("invalid-cpf", "CPF is not valid");
        }
        return new Cpf(digits);
    }

    public static boolean isValid(String raw) {
        return hasValidCheckDigits(normalize(raw));
    }

    /** Computes the two check digits for a 9-digit base. Package-private for tests. */
    static String checkDigitsFor(String nineDigits) {
        int first = checkDigit(nineDigits);
        int second = checkDigit(nineDigits + first);
        return "" + first + second;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(".", "").replace("-", "").strip();
    }

    private static boolean hasValidCheckDigits(String digits) {
        if (digits.length() != LENGTH || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        // "000.000.000-00", "111.111.111-11" and so on pass the arithmetic but are not issued.
        if (digits.chars().distinct().count() == 1) {
            return false;
        }
        return digits.substring(9).equals(checkDigitsFor(digits.substring(0, 9)));
    }

    /** Weights start at length + 1 and go down to 2; remainder below 2 gives digit 0. */
    private static int checkDigit(String base) {
        int sum = 0;
        int weight = base.length() + 1;
        for (int i = 0; i < base.length(); i++) {
            sum += (base.charAt(i) - '0') * weight--;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    public String value() {
        return digits;
    }

    /** Only the check digits stay visible: {@code ***.***.***-09}. */
    public String masked() {
        return "***.***.***-" + digits.substring(9);
    }

    @Override
    public String toString() {
        return masked();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cpf cpf && digits.equals(cpf.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(digits);
    }
}
