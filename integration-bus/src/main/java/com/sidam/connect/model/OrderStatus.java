package com.sidam.connect.model;

/** Cycle de vie d'une commande dans le bus (voir docs/prd/03_user_flows.md). */
public enum OrderStatus {
    RECEIVED,
    VALIDATING,
    STOCK_RESERVED,
    INVOICED,
    REJECTED_OUT_OF_STOCK,
    RETRYING,
    FAILED,
    DELIVERY_ASSIGNED,
    IN_DELIVERY,
    DELIVERED
}
