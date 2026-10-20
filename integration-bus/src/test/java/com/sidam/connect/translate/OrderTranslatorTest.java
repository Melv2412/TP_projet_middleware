package com.sidam.connect.translate;

import com.sidam.connect.model.CanonicalOrder;
import com.sidam.connect.model.DeliveryMissionMessage;
import com.sidam.connect.model.DeliveryStatusMessage;
import com.sidam.connect.model.ErpCommandeXml;
import com.sidam.connect.model.StoreOrderMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTranslatorTest {

    private final OrderTranslator translator = new OrderTranslator();
    private final Map<String, BigDecimal> prices = Map.of(
            "TV-SAM-55", new BigDecimal("350000"),
            "FR-LG-01", new BigDecimal("425000"));

    private static final String STORE_JSON = """
            {"orderId":"CMD-2026-0042","correlationId":"6f1c2a43-8b0e-4a7e-9d38-2a1f0c7b9e11",
             "customerId":"CLI-001","shippingAddress":"Cocody, Abidjan",
             "items":[{"sku":"TV-SAM-55","quantity":1},{"sku":"FR-LG-01","quantity":1}]}
            """;

    @Test
    void storeJsonDevientCanoniqueAvecPrixEtTotal() throws Exception {
        CanonicalOrder order = translator.toCanonical(translator.parseStoreJson(STORE_JSON), prices);

        assertThat(order.getOrderId()).isEqualTo("CMD-2026-0042");
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0).getUnitPrice()).isEqualByComparingTo("350000");
        assertThat(order.getTotal()).isEqualByComparingTo("775000");
    }

    @Test
    void articleInconnuResteSansPrix() throws Exception {
        StoreOrderMessage store = translator.parseStoreJson(STORE_JSON);
        store.getItems().add(new StoreOrderMessage.Item("INCONNU", 3));

        CanonicalOrder order = translator.toCanonical(store, prices);

        assertThat(order.getItems().get(2).getUnitPrice()).isNull();
        assertThat(order.getTotal()).isEqualByComparingTo("775000");
    }

    @Test
    void canoniqueDevientXmlErpLegacy() throws Exception {
        CanonicalOrder order = translator.toCanonical(translator.parseStoreJson(STORE_JSON), prices);

        String xml = translator.toErpXml(order);

        assertThat(xml).startsWith("<commande><ref>CMD-2026-0042</ref>");
        assertThat(xml).contains("<lignes><ligne><article>TV-SAM-55</article><qte>1</qte>");
        assertThat(xml).contains("<montantTotal>775000</montantTotal>");
    }

    @Test
    void canoniqueDevientMissionLivreurAvecDesignations() throws Exception {
        CanonicalOrder order = translator.toCanonical(translator.parseStoreJson(STORE_JSON), prices);

        DeliveryMissionMessage mission = translator.toDeliveryMission(order,
                Map.of("TV-SAM-55", "Téléviseur Samsung 55 pouces"));

        assertThat(mission.getAdresse()).isEqualTo("Cocody, Abidjan");
        assertThat(mission.getColis()).hasSize(2);
        assertThat(mission.getColis().get(0).getDesignation()).isEqualTo("Téléviseur Samsung 55 pouces");
        assertThat(mission.getColis().get(1).getDesignation()).isEqualTo("FR-LG-01");   // désignation inconnue → SKU
        assertThat(mission.getMontant()).isEqualByComparingTo("775000");
    }

    @Test
    void statutLivreDevientAvisDeLivraisonXml() throws Exception {
        DeliveryStatusMessage status = new DeliveryStatusMessage("LIV-2026-0001", "CMD-2026-0042", "x",
                DeliveryStatusMessage.Status.DELIVERED, "Koffi", Instant.parse("2026-10-20T16:00:00Z"));

        String xml = translator.toErpDeliveryXml(status);

        assertThat(xml).isEqualTo("<livraison><ref>CMD-2026-0042</ref><mission>LIV-2026-0001</mission>"
                + "<statut>LIVREE</statut><livreur>Koffi</livreur><dateLivraison>2026-10-20T16:00:00Z</dateLivraison></livraison>");
        assertThat(translator.parseErpDeliveryXml(xml).getRef()).isEqualTo("CMD-2026-0042");
    }

    @Test
    void lAllerRetourXmlConserveLesLignes() throws Exception {
        CanonicalOrder order = translator.toCanonical(translator.parseStoreJson(STORE_JSON), prices);

        ErpCommandeXml parsed = translator.parseErpXml(translator.toErpXml(order));

        assertThat(parsed.getRef()).isEqualTo("CMD-2026-0042");
        assertThat(parsed.getLignes()).extracting(ErpCommandeXml.Ligne::getArticle)
                .containsExactly("TV-SAM-55", "FR-LG-01");
    }
}
