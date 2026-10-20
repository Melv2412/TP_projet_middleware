package com.sidam.connect.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Avis de livraison au format XML de l'ERP legacy :
 * <pre>&lt;livraison&gt;&lt;ref/&gt;&lt;mission/&gt;&lt;statut&gt;LIVREE&lt;/statut&gt;&lt;livreur/&gt;&lt;dateLivraison/&gt;&lt;/livraison&gt;</pre>
 */
@JacksonXmlRootElement(localName = "livraison")
@JsonPropertyOrder({"ref", "mission", "statut", "livreur", "dateLivraison"})
public class ErpLivraisonXml {

    private String ref;
    private String mission;
    private String statut;
    private String livreur;
    private String dateLivraison;

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getMission() { return mission; }
    public void setMission(String mission) { this.mission = mission; }
    public String getStatut() { return statut; }
    public void setStatut(String statut) { this.statut = statut; }
    public String getLivreur() { return livreur; }
    public void setLivreur(String livreur) { this.livreur = livreur; }
    public String getDateLivraison() { return dateLivraison; }
    public void setDateLivraison(String dateLivraison) { this.dateLivraison = dateLivraison; }
}
