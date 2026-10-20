package com.sidam.connect.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Verdict global sur le stock, construit par l'Aggregator à partir des lignes. */
public class StockVerdict {

    private final List<StockCheckResult> lines = new ArrayList<>();

    public void add(StockCheckResult line) {
        lines.add(line);
    }

    public List<StockCheckResult> getLines() {
        return lines;
    }

    public boolean isAllAvailable() {
        return !lines.isEmpty() && lines.stream().allMatch(StockCheckResult::ok);
    }

    /** Explication lisible des lignes refusées, ex. "LAVE-01 : demandé 5, disponible 2". */
    public String rejectionReason() {
        return lines.stream()
                .filter(l -> !l.ok())
                .map(StockCheckResult::reason)
                .collect(Collectors.joining(" ; "));
    }
}
