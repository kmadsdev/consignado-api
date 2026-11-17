package io.github.kmadsdev.consignado.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** One ordering for every listing: newest first, with the id as a tie-breaker so pages are stable. */
final class Paging {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private Paging() {
    }

    static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, NEWEST_FIRST);
    }
}
