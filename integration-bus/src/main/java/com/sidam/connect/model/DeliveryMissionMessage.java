package com.sidam.connect.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Mission au format JSON de l'application SIDAM Delivery (bus → app livreur). */
public class DeliveryMissionMessage {

    private String orderId;
    private String correlationId;
    private String client;
    private String adresse;
    private List<Colis> colis = new ArrayList<>();
    private BigDecimal montant;

    public static class Colis {
        private String article;
        private String designation;
        private int quantite;

        public Colis() {
        }

        public Colis(String article, String designation, int quantite) {
            this.article = article;
            this.designation = designation;
            this.quantite = quantite;
        }

        public String getArticle() { return article; }
        public void setArticle(String article) { this.article = article; }
        public String getDesignation() { return designation; }
        public void setDesignation(String designation) { this.designation = designation; }
        public int getQuantite() { return quantite; }
        public void setQuantite(int quantite) { this.quantite = quantite; }
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }
    public String getAdresse() { return adresse; }
    public void setAdresse(String adresse) { this.adresse = adresse; }
    public List<Colis> getColis() { return colis; }
    public void setColis(List<Colis> colis) { this.colis = colis; }
    public BigDecimal getMontant() { return montant; }
    public void setMontant(BigDecimal montant) { this.montant = montant; }
}
