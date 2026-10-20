package com.sidam.connect.translate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sidam.connect.model.CanonicalOrder;
import com.sidam.connect.model.DeliveryMissionMessage;
import com.sidam.connect.model.DeliveryStatusMessage;
import com.sidam.connect.model.ErpCommandeXml;
import com.sidam.connect.model.ErpLivraisonXml;
import com.sidam.connect.model.OrderLine;
import com.sidam.connect.model.StoreOrderMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * EIP Message Translator : conversions entre les formats des différents systèmes.
 * <ul>
 *   <li>Store (JSON) → modèle canonique, enrichi avec les prix ERP (Content Enricher)</li>
 *   <li>modèle canonique → XML ERP legacy</li>
 * </ul>
 */
@Component
public class OrderTranslator {

    // Les champs vides ne sont pas écrits : un message montre exactement ce que le système a envoyé.
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final XmlMapper xml = new XmlMapper();

    public StoreOrderMessage parseStoreJson(String body) throws JsonProcessingException {
        return json.readValue(body, StoreOrderMessage.class);
    }

    public String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sérialisation JSON impossible", e);
        }
    }

    /**
     * Store JSON → modèle canonique. Les prix viennent du catalogue ERP ;
     * un article inconnu reste sans prix (il sera refusé à la vérification de stock).
     */
    public CanonicalOrder toCanonical(StoreOrderMessage store, Map<String, BigDecimal> erpPrices) {
        CanonicalOrder order = new CanonicalOrder();
        order.setOrderId(store.getOrderId());
        order.setCorrelationId(store.getCorrelationId());
        order.setCustomerId(store.getCustomerId());
        order.setShippingAddress(store.getShippingAddress());
        order.setCreatedAt(Instant.now());

        BigDecimal total = BigDecimal.ZERO;
        for (StoreOrderMessage.Item item : store.getItems()) {
            BigDecimal price = erpPrices.get(item.getSku());
            order.getItems().add(new OrderLine(item.getSku(), item.getQuantity(), price));
            if (price != null) {
                total = total.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        order.setTotal(total);
        return order;
    }

    /** Modèle canonique → XML imposé par l'ERP legacy. */
    public String toErpXml(CanonicalOrder order) {
        ErpCommandeXml cmd = new ErpCommandeXml();
        cmd.setRef(order.getOrderId());
        cmd.setClient(order.getCustomerId());
        cmd.setAdresseLivraison(order.getShippingAddress());
        for (OrderLine line : order.getItems()) {
            cmd.getLignes().add(new ErpCommandeXml.Ligne(line.getSku(), line.getQuantity(), line.getUnitPrice()));
        }
        cmd.setMontantTotal(order.getTotal());
        try {
            return xml.writeValueAsString(cmd);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Conversion XML impossible", e);
        }
    }

    /** Lecture du XML côté ERP. */
    public ErpCommandeXml parseErpXml(String body) throws JsonProcessingException {
        return xml.readValue(body, ErpCommandeXml.class);
    }

    /** Modèle canonique → mission JSON de l'app livreur (désignations ajoutées depuis le catalogue ERP). */
    public DeliveryMissionMessage toDeliveryMission(CanonicalOrder order, Map<String, String> productNames) {
        DeliveryMissionMessage mission = new DeliveryMissionMessage();
        mission.setOrderId(order.getOrderId());
        mission.setCorrelationId(order.getCorrelationId());
        mission.setClient(order.getCustomerId());
        mission.setAdresse(order.getShippingAddress());
        for (OrderLine line : order.getItems()) {
            mission.getColis().add(new DeliveryMissionMessage.Colis(
                    line.getSku(), productNames.getOrDefault(line.getSku(), line.getSku()), line.getQuantity()));
        }
        mission.setMontant(order.getTotal());
        return mission;
    }

    public <T> T parseJson(String body, Class<T> type) throws JsonProcessingException {
        return json.readValue(body, type);
    }

    /** Statut de livraison (JSON app livreur) → avis de livraison XML pour l'ERP legacy. */
    public String toErpDeliveryXml(DeliveryStatusMessage status) {
        ErpLivraisonXml livraison = new ErpLivraisonXml();
        livraison.setRef(status.getOrderId());
        livraison.setMission(status.getMissionId());
        livraison.setStatut("LIVREE");
        livraison.setLivreur(status.getDriver());
        livraison.setDateLivraison(status.getAt() == null ? Instant.now().toString() : status.getAt().toString());
        try {
            return xml.writeValueAsString(livraison);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Conversion XML impossible", e);
        }
    }

    public ErpLivraisonXml parseErpDeliveryXml(String body) throws JsonProcessingException {
        return xml.readValue(body, ErpLivraisonXml.class);
    }
}
