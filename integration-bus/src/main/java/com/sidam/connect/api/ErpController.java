package com.sidam.connect.api;

import com.sidam.connect.erp.LegacyErpAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Consultation de l'ERP legacy (stock, commandes reçues en XML, factures). */
@RestController
@RequestMapping("/api/erp")
public class ErpController {

    private final LegacyErpAdapter erp;

    public ErpController(LegacyErpAdapter erp) {
        this.erp = erp;
    }

    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory() {
        return erp.inventory();
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return erp.orders();
    }

    @GetMapping("/orders/{ref}")
    public ResponseEntity<Map<String, Object>> order(@PathVariable String ref) {
        return ResponseEntity.of(erp.findOrder(ref));
    }
}
