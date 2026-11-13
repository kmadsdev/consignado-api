package io.github.kmadsdev.consignado.testsupport;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates synthetic CPFs with valid check digits for tests. No real CPF is stored
 * anywhere in this repository. The algorithm is written again here on purpose, so the
 * tests do not just ask the production code to check itself.
 */
public final class TestCpfs {

    private TestCpfs() {
    }

    public static String random() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String base;
        do {
            StringBuilder sb = new StringBuilder(9);
            for (int i = 0; i < 9; i++) {
                sb.append(rnd.nextInt(10));
            }
            base = sb.toString();
        } while (base.chars().distinct().count() == 1);
        int d1 = digit(base, 10);
        int d2 = digit(base + d1, 11);
        return base + d1 + d2;
    }

    public static String formatted(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    private static int digit(String s, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            sum += Character.getNumericValue(s.charAt(i)) * (firstWeight - i);
        }
        int mod = (sum * 10) % 11;
        return mod == 10 ? 0 : mod;
    }
}
