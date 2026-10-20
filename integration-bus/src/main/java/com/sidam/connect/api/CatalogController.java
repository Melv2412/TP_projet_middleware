package com.sidam.connect.api;

import com.sidam.connect.erp.ErpUnavailableException;
import com.sidam.connect.erp.LegacyErpAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Catalogue vu par le Store. Le Store ne lit jamais l'ERP directement :
 * il passe par le bus, qui interroge l'ERP via son adaptateur.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final LegacyErpAdapter erp;

    public CatalogController(LegacyErpAdapter erp) {
        this.erp = erp;
    }

    @GetMapping
    public ResponseEntity<?> catalog() {
        try {
            erp.priceCatalog();   // même accès que le bus : échoue si l'ERP est en panne
        } catch (ErpUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Catalogue momentanément indisponible (ERP injoignable)"));
        }
        List<Map<String, Object>> items = erp.inventory().stream()
                .map(p -> Map.<String, Object>of(
                        "sku", p.get("sku"),
                        "name", p.get("name"),
                        "price", p.get("unit_price"),
                        "available", p.get("available")))
                .toList();
        return ResponseEntity.ok(items);
    }
}
