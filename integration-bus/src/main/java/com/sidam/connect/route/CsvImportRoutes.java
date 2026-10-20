package com.sidam.connect.route;

import com.sidam.connect.model.StoreOrderMessage;
import com.sidam.connect.order.OrderIntakeService;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AggregationStrategies;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Import CSV au format historique de l'ERP (order_id,sku,quantity,unit_price).
 * <pre>
 * CSV ─▶ unmarshal (lignes) ─▶ regroupement par order_id ─▶ Message Translator (CSV → JSON Store)
 *     ─▶ Splitter (une commande à la fois) ─▶ même circuit que le Store (RabbitMQ, stock, ERP…)
 * </pre>
 * Les prix du fichier sont ignorés : c'est le catalogue ERP qui fait foi (Content Enricher).
 */
@Component
public class CsvImportRoutes extends RouteBuilder {

    private static final List<String> COLUMNS = List.of("order_id", "sku", "quantity", "unit_price");

    private final OrderIntakeService intake;

    public CsvImportRoutes(OrderIntakeService intake) {
        this.intake = intake;
    }

    /** Une commande reconstituée à partir de ses lignes CSV. */
    record CsvOrder(String csvOrderId, StoreOrderMessage message, String csvLines) {
    }

    @Override
    public void configure() {
        // Appel synchrone depuis l'API : une erreur de fichier doit remonter en 400.
        errorHandler(noErrorHandler());

        CsvDataFormat csv = new CsvDataFormat();
        csv.setUseMaps(true);           // la première ligne donne le nom des colonnes
        csv.setIgnoreEmptyLines(true);
        csv.setTrim(true);

        from("direct:importCsv").routeId("csv-import")
                .unmarshal(csv)
                .process(this::groupByOrder)
                .split(body(), AggregationStrategies.flexible(Map.class).accumulateInCollection(ArrayList.class))
                    .process(this::register)
                    .setProperty("importResult", body())
                    .setBody(exchangeProperty("csvMessage"))
                    .to("direct:submitOrder")
                    .setBody(exchangeProperty("importResult"))
                .end();
    }

    @SuppressWarnings("unchecked")
    private void groupByOrder(Exchange e) {
        List<Map<String, String>> rows = e.getIn().getBody(List.class);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Fichier CSV vide");
        }
        if (!rows.get(0).keySet().containsAll(COLUMNS)) {
            throw new IllegalArgumentException("En-tête CSV attendu : " + String.join(",", COLUMNS));
        }

        Map<String, CsvOrderBuilder> byOrder = new LinkedHashMap<>();
        int lineNo = 1;
        for (Map<String, String> row : rows) {
            lineNo++;
            String orderId = row.get("order_id");
            if (orderId == null || orderId.isBlank()) {
                throw new IllegalArgumentException("Ligne " + lineNo + " : order_id manquant");
            }
            int quantity;
            try {
                quantity = Integer.parseInt(row.get("quantity"));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Ligne " + lineNo + " : quantité invalide « " + row.get("quantity") + " »");
            }
            byOrder.computeIfAbsent(orderId, CsvOrderBuilder::new)
                    .add(row.get("sku"), quantity, String.join(",", orderId, row.get("sku"), row.get("quantity"),
                            row.getOrDefault("unit_price", "")));
        }
        // Tout le fichier est validé avant d'enregistrer la moindre commande.
        List<CsvOrder> result = byOrder.values().stream().map(CsvOrderBuilder::build).toList();
        for (CsvOrder order : result) {
            try {
                OrderIntakeService.validate(order.message());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Commande " + order.csvOrderId() + " : " + ex.getMessage());
            }
        }
        e.getIn().setBody(result);
    }

    private void register(Exchange e) {
        CsvOrder order = e.getIn().getBody(CsvOrder.class);
        String header = String.join(",", COLUMNS);
        StoreOrderMessage msg = intake.register(order.message(), "ERP_CSV",
                "Commande importée depuis CSV (" + order.csvOrderId() + ", "
                        + order.message().getItems().size() + " ligne(s)) — CSV → JSON Store",
                header + "\n" + order.csvLines(), "CSV");
        e.setProperty("csvMessage", msg);
        e.getIn().setBody(Map.of("csvOrderId", order.csvOrderId(), "orderId", msg.getOrderId()));
    }

    /** Accumule les lignes CSV d'un même order_id. */
    private static final class CsvOrderBuilder {
        private final String csvOrderId;
        private final StoreOrderMessage message = new StoreOrderMessage();
        private final StringBuilder lines = new StringBuilder();

        CsvOrderBuilder(String csvOrderId) {
            this.csvOrderId = csvOrderId;
            message.setCustomerId("CSV-" + csvOrderId);
            message.setItems(new ArrayList<>());
        }

        void add(String sku, int quantity, String line) {
            message.getItems().add(new StoreOrderMessage.Item(sku, quantity));
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append(line);
        }

        CsvOrder build() {
            return new CsvOrder(csvOrderId, message, lines.toString());
        }
    }
}
