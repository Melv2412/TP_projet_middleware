package com.sidam.connect.route;

import com.sidam.connect.model.StockCheckResult;
import com.sidam.connect.model.StockVerdict;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockAggregationStrategyTest {

    private final CamelContext context = new DefaultCamelContext();
    private final StockAggregationStrategy strategy = new StockAggregationStrategy();

    private Exchange line(StockCheckResult result) {
        Exchange e = new DefaultExchange(context);
        e.getIn().setBody(result);
        return e;
    }

    @Test
    void toutesLesLignesDisponibles() {
        Exchange agg = strategy.aggregate(null, line(new StockCheckResult("TV-SAM-55", 1, 10, true, null)));
        agg = strategy.aggregate(agg, line(new StockCheckResult("FR-LG-01", 1, 6, true, null)));

        StockVerdict verdict = agg.getIn().getBody(StockVerdict.class);
        assertThat(verdict.getLines()).hasSize(2);
        assertThat(verdict.isAllAvailable()).isTrue();
    }

    @Test
    void uneLigneEnRuptureRendLaCommandeIndisponible() {
        Exchange agg = strategy.aggregate(null, line(new StockCheckResult("TV-SAM-55", 1, 10, true, null)));
        agg = strategy.aggregate(agg, line(new StockCheckResult("LAVE-01", 5, 2, false, "LAVE-01 : demandé 5, disponible 2")));

        StockVerdict verdict = agg.getIn().getBody(StockVerdict.class);
        assertThat(verdict.isAllAvailable()).isFalse();
        assertThat(verdict.rejectionReason()).isEqualTo("LAVE-01 : demandé 5, disponible 2");
    }
}
