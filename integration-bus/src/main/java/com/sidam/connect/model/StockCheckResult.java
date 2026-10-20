package com.sidam.connect.model;

/** Résultat de la vérification de stock d'une seule ligne (produit par le Splitter). */
public record StockCheckResult(String sku, int requested, int available, boolean ok, String reason) {
}
