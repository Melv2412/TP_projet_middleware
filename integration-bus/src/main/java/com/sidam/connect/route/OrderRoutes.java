package com.sidam.connect.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sidam.connect.audit.EventLog;
import com.sidam.connect.audit.IntegrationEvent;
import com.sidam.connect.erp.ErpUnavailableException;
import com.sidam.connect.erp.LegacyErpAdapter;
import com.sidam.connect.model.CanonicalOrder;
import com.sidam.connect.model.ErpAck;
import com.sidam.connect.model.InvalidOrderMessageException;
import com.sidam.connect.model.OrderLine;
import com.sidam.connect.model.OrderStatus;
import com.sidam.connect.model.StockCheckResult;
import com.sidam.connect.model.StockVerdict;
import com.sidam.connect.model.StoreOrderMessage;
import com.sidam.connect.order.OrderIntakeService;
import com.sidam.connect.order.OrderRepository;
import com.sidam.connect.translate.OrderTranslator;
import com.sidam.connect.model.DeliveryStatusMessage;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.sidam.connect.route.Headers.CORRELATION_ID;
import static com.sidam.connect.route.Headers.ERROR_CODE;
import static com.sidam.connect.route.Headers.ERR_ERP_UNAVAILABLE;
import static com.sidam.connect.route.Headers.ERR_INVALID_PAYLOAD;
import static com.sidam.connect.route.Headers.ERR_TECHNICAL;
import static com.sidam.connect.route.Headers.ORDER_ID;
import static com.sidam.connect.route.Headers.PROP_INVOICED;
import static com.sidam.connect.route.Headers.PROP_ORDER;
import static com.sidam.connect.route.Headers.PROP_REJECT_REASON;
import static com.sidam.connect.route.Headers.PROP_REPLAY_ROUTING_KEY;
import static com.sidam.connect.route.Headers.PROP_STALE;
import static com.sidam.connect.route.Headers.PROP_ERP_XML;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Traitement d'une commande depuis RabbitMQ :
 * <pre>
 * RabbitMQ ─▶ order-intake (Idempotent Consumer, Translator + Enricher)
 *          ─▶ stock-check (Splitter ─▶ Aggregator ─▶ Content-Based Router)
 *          ─▶ erp-submit (Translator Canonical → XML, réservation + facture)  ou  order-reject
 * Erreur technique ─▶ Retry ×3 (2 s, 4 s, 8 s) ─▶ Dead Letter Channel (sidam.orders.dlq)
 * Message invalide ─▶ Dead Letter Channel directement
 * </pre>
 * La publication (API ─▶ RabbitMQ) est dans {@link SubmitRoutes}.
 */
@Component
public class OrderRoutes extends RouteBuilder {

    private final OrderTranslator translator;
    private final LegacyErpAdapter erp;
    private final OrderRepository orders;
    private final EventLog events;

    @Value("${sidam.rabbit.exchange}")
    private String exchangeName;
    @Value("${sidam.rabbit.orders-queue}")
    private String ordersQueue;
    @Value("${sidam.rabbit.orders-routing-key}")
    private String ordersRoutingKey;
    @Value("${sidam.rabbit.concurrent-consumers}")
    private int concurrentConsumers;
    @Value("${sidam.rabbit.delivery-assigned-routing-key}")
    private String deliveryAssignedRoutingKey;
    @Value("${sidam.rabbit.delivery-status-queue}")
    private String deliveryStatusQueue;
    @Value("${sidam.rabbit.delivery-status-routing-key}")
    private String deliveryStatusRoutingKey;
    @Value("${sidam.retry.max-attempts}")
    private int maxAttempts;
    @Value("${sidam.retry.initial-delay-ms}")
    private long initialDelayMs;
    @Value("${sidam.retry.multiplier}")
    private double multiplier;

    private final JdbcIdempotentRepository idempotentRepository;

    public OrderRoutes(OrderTranslator translator, LegacyErpAdapter erp, OrderRepository orders, EventLog events,
                       JdbcIdempotentRepository idempotentRepository) {
        this.translator = translator;
        this.erp = erp;
        this.orders = orders;
        this.events = events;
        this.idempotentRepository = idempotentRepository;
    }

    @Override
    public void configure() {
        // File durable : les commandes survivent à un redémarrage de RabbitMQ (zéro perte).
        // Plusieurs consommateurs en parallèle : la réservation atomique empêche la survente.
        String rabbitConsumer = "spring-rabbitmq:" + exchangeName + "?queues=" + ordersQueue
                + "&routingKey=" + ordersRoutingKey + "&autoDeclare=true&arg.queue.durable=true"
                + "&concurrentConsumers=" + concurrentConsumers;

        // Erreur technique (ERP injoignable…) : Retry avec délai croissant, puis Dead Letter Channel.
        // useOriginalMessage : c'est le message reçu de RabbitMQ, intact, qui part en DLQ.
        errorHandler(deadLetterChannel("direct:deadLetter")
                .useOriginalMessage()
                .maximumRedeliveries(maxAttempts)
                .redeliveryDelay(initialDelayMs)
                .useExponentialBackOff()
                .backOffMultiplier(multiplier)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .onRedelivery(this::recordRetry));

        // Message illisible ou incomplet : inutile de réessayer, DLQ directe.
        onException(JsonProcessingException.class, InvalidOrderMessageException.class)
                .handled(true)
                .maximumRedeliveries(0)
                .useOriginalMessage()
                .setHeader(ERROR_CODE, constant(ERR_INVALID_PAYLOAD))
                .to("direct:deadLetter");

        // 2. Consommation → Idempotent Consumer → Message Translator (JSON Store → canonique)
        //    + Content Enricher (prix ERP)
        from(rabbitConsumer).routeId("order-intake")
                .setProperty(PROP_REPLAY_ROUTING_KEY, constant(ordersRoutingKey))
                .convertBodyTo(String.class)
                .process(e -> {
                    StoreOrderMessage store = translator.parseStoreJson(e.getIn().getBody(String.class));
                    checkMessage(store);
                    e.getIn().setHeader(ORDER_ID, store.getOrderId());
                    e.getIn().setHeader(CORRELATION_ID, store.getCorrelationId());
                    e.getIn().setBody(store);
                })
                // Un même message (même commande, même type) n'est traité qu'une seule fois.
                .idempotentConsumer(simple("ORDER_SUBMITTED:${header." + ORDER_ID + "}"))
                    .idempotentRepository(idempotentRepository)
                    .skipDuplicate(false)
                    .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(true))
                        .process(e -> events.record(event(e, "DUPLICATE_IGNORED")
                                .route("order-intake", "Idempotent Consumer")
                                .flow("RABBITMQ", "BUS")
                                .message("Message déjà traité pour " + orderId(e) + " : ignoré, aucun nouvel effet")))
                        .stop()
                    .end()
                    .process(e -> {
                        long start = System.currentTimeMillis();
                        StoreOrderMessage store = e.getIn().getBody(StoreOrderMessage.class);
                        CanonicalOrder order = translator.toCanonical(store, erp.priceCatalog());
                        orders.savePricing(order);
                        orders.updateStatus(order.getOrderId(), OrderStatus.VALIDATING, null);

                        events.record(event(e, "TRANSFORMED")
                                .route("order-intake", "Message Translator + Content Enricher")
                                .flow("RABBITMQ", "BUS")
                                .message("JSON Store → modèle canonique (prix ERP ajoutés, total " + order.getTotal() + " FCFA)")
                                .payload(translator.toJson(order), "JSON")
                                .duration(System.currentTimeMillis() - start));

                        e.setProperty(PROP_ORDER, order);
                        e.getIn().setBody(order);
                    })
                    .to("direct:checkStock")
                .end();

        // 3. Splitter (une vérification par ligne) → Aggregator (verdict) → Content-Based Router
        from("direct:checkStock").routeId("stock-check")
                .split(simple("${body.items}"), new StockAggregationStrategy())
                    .process(e -> {
                        OrderLine line = e.getIn().getBody(OrderLine.class);
                        StockCheckResult result = erp.checkLine(line.getSku(), line.getQuantity());
                        events.record(event(e, "STOCK_LINE_CHECKED")
                                .route("stock-check", "Splitter")
                                .flow("BUS", "ERP")
                                .message(result.sku() + " : demandé " + result.requested() + ", disponible "
                                        + result.available() + (result.ok() ? " → OK" : " → INSUFFISANT")));
                        e.getIn().setBody(result);
                    })
                .end()
                .process(e -> {
                    StockVerdict verdict = e.getIn().getBody(StockVerdict.class);
                    events.record(event(e, "STOCK_AGGREGATED")
                            .route("stock-check", "Aggregator")
                            .message(verdict.getLines().size() + " ligne(s) vérifiée(s) : "
                                    + (verdict.isAllAvailable() ? "stock disponible" : verdict.rejectionReason())));
                })
                .choice()
                    .when(e -> e.getIn().getBody(StockVerdict.class).isAllAvailable())
                        .process(e -> {
                            events.record(event(e, "ROUTED")
                                    .route("stock-check", "Content-Based Router")
                                    .flow("BUS", "ERP")
                                    .message("Stock disponible → envoi à l'ERP"));
                            e.getIn().setBody(e.getProperty(PROP_ORDER));
                        })
                        .to("direct:erpSubmit")
                    .otherwise()
                        .process(e -> {
                            String reason = e.getIn().getBody(StockVerdict.class).rejectionReason();
                            e.setProperty(PROP_REJECT_REASON, reason);
                            events.record(event(e, "ROUTED")
                                    .route("stock-check", "Content-Based Router")
                                    .flow("BUS", "STORE")
                                    .message("Rupture de stock → canal de rejet"));
                        })
                        .to("direct:rejectOrder")
                .end();

        // 4. Message Translator (canonique → XML ERP) puis réservation atomique + facture dans l'ERP
        from("direct:erpSubmit").routeId("erp-submit")
                .process(e -> {
                    long start = System.currentTimeMillis();
                    CanonicalOrder order = e.getIn().getBody(CanonicalOrder.class);
                    String xml = translator.toErpXml(order);
                    events.record(event(e, "TRANSFORMED")
                            .route("erp-submit", "Message Translator")
                            .flow("BUS", "ERP")
                            .message("Modèle canonique → XML ERP legacy")
                            .payload(xml, "XML")
                            .duration(System.currentTimeMillis() - start));
                    e.getIn().setBody(xml);
                })
                .bean(erp, "receiveOrderXml")
                .process(this::handleErpAck)
                .choice()
                    .when(exchangeProperty(PROP_REJECT_REASON).isNotNull())
                        .to("direct:rejectOrder")
                    .when(exchangeProperty(PROP_INVOICED).isEqualTo(true))
                        .to("direct:dispatchDelivery")
                .end();

        // 6. Process Manager : commande facturée → mission envoyée à l'app livreur (JSON)
        from("direct:dispatchDelivery").routeId("delivery-dispatch")
                .process(e -> {
                    CanonicalOrder order = e.getProperty(PROP_ORDER, CanonicalOrder.class);
                    String json = translator.toJson(translator.toDeliveryMission(order, erp.productNames()));
                    events.record(event(e, "DISPATCHED")
                            .route("delivery-dispatch", "Message Translator + Process Manager")
                            .flow("BUS", "DELIVERY")
                            .message("Mission de livraison envoyée à SIDAM Delivery")
                            .payload(json, "JSON"));
                    e.getIn().setBody(json);
                })
                .setExchangePattern(ExchangePattern.InOnly)
                .to("spring-rabbitmq:" + exchangeName + "?routingKey=" + deliveryAssignedRoutingKey);

        // 7. Retour de l'app livreur : Content-Based Router sur le statut + Process Manager
        //    (un statut déjà connu ou plus ancien est ignoré : ASSIGNED < IN_DELIVERY < DELIVERED)
        from("spring-rabbitmq:" + exchangeName + "?queues=" + deliveryStatusQueue
                + "&routingKey=" + deliveryStatusRoutingKey + "&autoDeclare=true&arg.queue.durable=true")
                .routeId("delivery-status")
                .setProperty(PROP_REPLAY_ROUTING_KEY, constant(deliveryStatusRoutingKey))
                .convertBodyTo(String.class)
                .process(this::readDeliveryStatus)
                .choice()
                    .when(exchangeProperty(PROP_STALE).isEqualTo(true))
                        .process(e -> {
                            DeliveryStatusMessage msg = e.getIn().getBody(DeliveryStatusMessage.class);
                            events.record(event(e, "DUPLICATE_IGNORED")
                                    .route("delivery-status", "Process Manager")
                                    .flow("DELIVERY", "BUS")
                                    .message("Statut " + msg.getStatus() + " déjà pris en compte : ignoré"));
                        })
                    .when(e -> e.getIn().getBody(DeliveryStatusMessage.class).getStatus() == DeliveryStatusMessage.Status.DELIVERED)
                        .process(e -> {
                            DeliveryStatusMessage msg = e.getIn().getBody(DeliveryStatusMessage.class);
                            String xml = translator.toErpDeliveryXml(msg);
                            events.record(event(e, "TRANSFORMED")
                                    .route("delivery-status", "Message Translator")
                                    .flow("BUS", "ERP")
                                    .message("Statut livreur JSON → avis de livraison XML ERP")
                                    .payload(xml, "XML"));
                            e.setProperty(PROP_ERP_XML, xml);
                        })
                        // Étape séparée : en cas de panne ERP, seul cet appel est retenté.
                        .process(e -> erp.receiveDeliveryXml(e.getProperty(PROP_ERP_XML, String.class)))
                        .process(this::recordDeliveryEvent)
                        .process(this::recordWorkflowCompleted)
                        .process(this::updateDeliveryStatus)
                    .otherwise()
                        .process(this::recordDeliveryEvent)
                        .process(this::updateDeliveryStatus)
                .end();

        // 5. Rejet métier : aucune réservation, aucune facture, aucune mission
        from("direct:rejectOrder").routeId("order-reject")
                .process(e -> {
                    String reason = e.getProperty(PROP_REJECT_REASON, String.class);
                    events.record(event(e, OrderStatus.REJECTED_OUT_OF_STOCK.name())
                            .route("order-reject", "Invalid Message Channel")
                            .flow("BUS", "STORE")
                            .message("Commande refusée : " + reason)
                            .error("OUT_OF_STOCK"));
                    orders.updateStatus(orderId(e), OrderStatus.REJECTED_OUT_OF_STOCK, reason);
                });
    }

    private void handleErpAck(Exchange e) {
        ErpAck ack = e.getIn().getBody(ErpAck.class);
        String orderId = orderId(e);

        if (!ack.reserved()) {
            // Course perdue entre la pré-vérification et la réservation (vente concurrente).
            e.setProperty(PROP_REJECT_REASON, ack.reason());
            return;
        }
        if (ack.duplicate()) {
            events.record(event(e, "DUPLICATE_IGNORED")
                    .route("erp-submit", "Idempotent Receiver (ERP)")
                    .flow("ERP", "BUS")
                    .message("L'ERP connaît déjà " + ack.ref() + " : aucun nouvel effet"));
            return;
        }

        // Règle : l'événement est journalisé avant le changement de statut.
        events.record(event(e, OrderStatus.STOCK_RESERVED.name())
                .route("erp-submit", "Request-Reply")
                .flow("ERP", "BUS")
                .message("Stock réservé dans l'ERP (transaction atomique)"));
        orders.updateStatus(orderId, OrderStatus.STOCK_RESERVED, null);

        events.record(event(e, OrderStatus.INVOICED.name())
                .route("erp-submit", "Request-Reply")
                .flow("ERP", "BUS")
                .message("Facture " + ack.invoiceNo() + " émise : " + ack.total() + " FCFA"));
        orders.updateStatus(orderId, OrderStatus.INVOICED, null);
        e.setProperty(PROP_INVOICED, true);
    }

    /** Lit le statut envoyé par l'app livreur et décide s'il fait avancer la commande. */
    private void readDeliveryStatus(Exchange e) throws JsonProcessingException {
        DeliveryStatusMessage msg = translator.parseJson(e.getIn().getBody(String.class), DeliveryStatusMessage.class);
        if (msg.getOrderId() == null || msg.getStatus() == null) {
            throw new InvalidOrderMessageException("Statut de livraison incomplet (orderId ou status manquant)");
        }
        Map<String, Object> order = orders.find(msg.getOrderId())
                .orElseThrow(() -> new InvalidOrderMessageException("Commande inconnue : " + msg.getOrderId()));
        e.getIn().setHeader(ORDER_ID, msg.getOrderId());
        e.getIn().setHeader(CORRELATION_ID, String.valueOf(order.get("correlation_id")));
        e.getIn().setBody(msg);

        String current = (String) order.get("delivery_status");
        int currentRank = current == null ? 0 : DeliveryStatusMessage.Status.valueOf(current).rank;
        e.setProperty(PROP_STALE, msg.getStatus().rank <= currentRank);
    }

    /** Met à jour le statut de livraison (après la journalisation de l'événement). */
    private void updateDeliveryStatus(Exchange e) {
        DeliveryStatusMessage msg = e.getIn().getBody(DeliveryStatusMessage.class);
        orders.updateDeliveryStatus(msg.getOrderId(), msg.getStatus().name(), msg.getStatus().orderStatus);
    }

    private void recordDeliveryEvent(Exchange e) {
        DeliveryStatusMessage msg = e.getIn().getBody(DeliveryStatusMessage.class);
        OrderStatus status = msg.getStatus().orderStatus;

        String text = switch (msg.getStatus()) {
            case ASSIGNED -> "Mission " + msg.getMissionId() + " créée dans SIDAM Delivery";
            case IN_DELIVERY -> "Mission " + msg.getMissionId() + " prise en charge par " + msg.getDriver();
            case DELIVERED -> "Commande livrée par " + msg.getDriver() + " ; ERP mis à jour";
        };
        events.record(event(e, status.name())
                .route("delivery-status", "Content-Based Router + Process Manager")
                .flow("DELIVERY", "BUS")
                .message(text)
                .payload(translator.toJson(msg), "JSON"));
    }

    private void recordWorkflowCompleted(Exchange e) {
        String orderId = orderId(e);
        long seconds = orders.find(orderId)
                .map(o -> Duration.between(((Timestamp) o.get("created_at")).toInstant(), Instant.now()).getSeconds())
                .orElse(0L);
        events.record(event(e, "WORKFLOW_COMPLETED")
                .route("delivery-status", "Process Manager")
                .flow("BUS", "STORE")
                .message("Parcours complet : commande → stock → facture → livraison (" + seconds + " s)")
                .duration(seconds * 1000));
    }

    /** Appelé avant chaque nouvelle tentative : rend le Retry visible dans le journal. */
    private void recordRetry(Exchange e) {
        int attempt = e.getIn().getHeader(Exchange.REDELIVERY_COUNTER, 1, Integer.class);
        int max = e.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER, maxAttempts, Integer.class);
        Throwable cause = e.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        if (cause == null) {
            cause = e.getException();
        }
        String reason = cause == null ? "erreur technique" : cause.getMessage();
        long delay = Math.round(initialDelayMs * Math.pow(multiplier, attempt - 1));

        String orderId = orderId(e);
        // Seul le traitement de la commande change son statut ; une livraison qui réessaie reste tracée.
        if (orderId != null && ordersRoutingKey.equals(e.getProperty(PROP_REPLAY_ROUTING_KEY))) {
            orders.updateStatus(orderId, OrderStatus.RETRYING, reason);
        }
        events.record(event(e, OrderStatus.RETRYING.name())
                .route(e.getFromRouteId(), "Retry")
                .message("Tentative " + attempt + "/" + max + " dans " + (delay / 1000.0) + " s : " + reason)
                .attempt(attempt)
                .error(cause instanceof ErpUnavailableException ? ERR_ERP_UNAVAILABLE : ERR_TECHNICAL));
    }

    /** Contrôle de structure du message reçu (au-delà du JSON bien formé). */
    private static void checkMessage(StoreOrderMessage store) {
        if (store.getOrderId() == null || store.getCorrelationId() == null) {
            throw new InvalidOrderMessageException("orderId ou correlationId manquant");
        }
        try {
            OrderIntakeService.validate(store);
        } catch (IllegalArgumentException ex) {
            throw new InvalidOrderMessageException(ex.getMessage());
        }
    }

    private static String orderId(Exchange e) {
        return e.getIn().getHeader(ORDER_ID, String.class);
    }

    private static IntegrationEvent event(Exchange e, String status) {
        return IntegrationEvent.of(orderId(e), e.getIn().getHeader(CORRELATION_ID, String.class), status);
    }
}
