package com.sidam.connect.route;

/** Noms des en-têtes transportés avec chaque message (EIP Correlation Identifier). */
public final class Headers {

    public static final String ORDER_ID = "orderId";
    public static final String CORRELATION_ID = "correlationId";

    /** En-têtes ajoutés aux messages envoyés en Dead Letter Queue. */
    public static final String DLQ_ID = "dlqId";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_REASON = "errorReason";

    /** Propriétés d'échange internes aux routes. */
    public static final String PROP_ORDER = "sidamOrder";
    public static final String PROP_REJECT_REASON = "sidamRejectReason";
    public static final String PROP_INVOICED = "sidamInvoiced";
    public static final String PROP_STALE = "sidamStaleStatus";
    public static final String PROP_ERP_XML = "sidamErpXml";
    /** Clé de routage de la file d'origine : un message rejoué depuis la DLQ y retourne. */
    public static final String PROP_REPLAY_ROUTING_KEY = "sidamReplayRoutingKey";
    public static final String REPLAY_ROUTING_KEY = "replayRoutingKey";

    /** Codes d'erreur. */
    public static final String ERR_ERP_UNAVAILABLE = "ERP_UNAVAILABLE";
    public static final String ERR_INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String ERR_TECHNICAL = "TECHNICAL_ERROR";

    private Headers() {
    }
}
