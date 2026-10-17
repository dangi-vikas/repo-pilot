package com.example.quality;

import java.util.List;

/**
 * A clean file in a defective fixture.
 *
 * <p>It is here so that "this file has no findings" is visible next to the files that have some. A
 * rule that fired everywhere would tell a reader nothing about where the problem is.
 */
public final class OrderService {

    private final OrderImporter importer;

    public OrderService(OrderImporter importer) {
        this.importer = importer;
    }

    public int importAll(List<String> rows) {
        return importer.importAll(rows);
    }
}
