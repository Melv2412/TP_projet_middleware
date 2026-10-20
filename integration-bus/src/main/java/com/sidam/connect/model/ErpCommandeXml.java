package com.sidam.connect.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrat XML imposé par l'ERP legacy (voir docs/prd/08_contrats_et_api.md).
 * <pre>
 * &lt;commande&gt;&lt;ref&gt;…&lt;/ref&gt;&lt;lignes&gt;&lt;ligne&gt;&lt;article/&gt;&lt;qte/&gt;&lt;/ligne&gt;&lt;/lignes&gt;&lt;/commande&gt;
 * </pre>
 */
@JacksonXmlRootElement(localName = "commande")
@JsonPropertyOrder({"ref", "client", "adresseLivraison", "lignes", "montantTotal"})
public class ErpCommandeXml {

    private String ref;
    private String client;
    private String adresseLivraison;

    @JacksonXmlElementWrapper(localName = "lignes")
    @JacksonXmlProperty(localName = "ligne")
    private List<Ligne> lignes = new ArrayList<>();

    private BigDecimal montantTotal;

    @JsonPropertyOrder({"article", "qte", "prixUnitaire"})
    public static class Ligne {
        private String article;
        private int qte;
        private BigDecimal prixUnitaire;

        public Ligne() {
        }

        public Ligne(String article, int qte, BigDecimal prixUnitaire) {
            this.article = article;
            this.qte = qte;
            this.prixUnitaire = prixUnitaire;
        }

        public String getArticle() { return article; }
        public void setArticle(String article) { this.article = article; }
        public int getQte() { return qte; }
        public void setQte(int qte) { this.qte = qte; }
        public BigDecimal getPrixUnitaire() { return prixUnitaire; }
        public void setPrixUnitaire(BigDecimal prixUnitaire) { this.prixUnitaire = prixUnitaire; }
    }

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }
    public String getAdresseLivraison() { return adresseLivraison; }
    public void setAdresseLivraison(String adresseLivraison) { this.adresseLivraison = adresseLivraison; }
    public List<Ligne> getLignes() { return lignes; }
    public void setLignes(List<Ligne> lignes) { this.lignes = lignes; }
    public BigDecimal getMontantTotal() { return montantTotal; }
    public void setMontantTotal(BigDecimal montantTotal) { this.montantTotal = montantTotal; }
}
