package com.sidam.connect.route;

import com.sidam.connect.model.StockCheckResult;
import com.sidam.connect.model.StockVerdict;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

/**
 * EIP Aggregator : regroupe les résultats de stock de chaque ligne (issus du Splitter)
 * en un seul {@link StockVerdict}.
 */
public class StockAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        StockCheckResult line = newExchange.getIn().getBody(StockCheckResult.class);
        if (oldExchange == null) {
            StockVerdict verdict = new StockVerdict();
            verdict.add(line);
            newExchange.getIn().setBody(verdict);
            return newExchange;
        }
        oldExchange.getIn().getBody(StockVerdict.class).add(line);
        return oldExchange;
    }
}
