package io.github.kmadsdev.consignado.api;

/** Bounds shared by every paged listing. */
final class Paging {

    /**
     * JPA takes the offset (page * size) as an int, so an unbounded page number ends in a 500.
     * 10,000 pages of 100 rows is far beyond what an offset listing should serve anyway.
     */
    static final int MAX_PAGE = 10_000;
    static final int MAX_SIZE = 100;
    static final String DEFAULT_SIZE = "20";

    private Paging() {
    }
}
