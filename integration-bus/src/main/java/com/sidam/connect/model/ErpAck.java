package com.sidam.connect.model;

import java.math.BigDecimal;

/**
 * Accusé de réception renvoyé par l'ERP après traitement d'une commande XML.
 *
 * @param reserved  true si tout le stock a été réservé
 * @param duplicate true si l'ERP connaissait déjà cette référence (aucun nouvel effet)
 */
public record ErpAck(String ref, boolean reserved, boolean duplicate, String invoiceNo,
                     BigDecimal total, String reason) {

    public static ErpAck rejected(String ref, String reason) {
        return new ErpAck(ref, false, false, null, null, reason);
    }
}
