package com.sidam.connect.erp;

import com.sidam.connect.model.CanonicalOrder;
import com.sidam.connect.model.ErpAck;
import com.sidam.connect.model.OrderLine;
import com.sidam.connect.translate.OrderTranslator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T09 au niveau ERP : 20 réservations lancées exactement en même temps sur un stock de 3,
 * sans la pré-vérification du Splitter. Seule la requête "UPDATE ... WHERE available >= qty"
 * protège le stock.
 * <p>
 * Nécessite la base PostgreSQL de docker-compose (localhost:5432). Ignoré si elle est absente.
 */
class LegacyErpAdapterConcurrencyTest {

    private static final String SKU = "TEST-RACE";
    private static final int STOCK = 3;
    private static final int BUYERS = 20;

    private JdbcTemplate jdbc;
    private LegacyErpAdapter erp;
    private final OrderTranslator translator = new OrderTranslator();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/sidam_db", "admin", "password");
        assumeTrue(isReachable(ds), "PostgreSQL non démarré : lancer docker compose up");

        jdbc = new JdbcTemplate(ds);
        erp = new LegacyErpAdapter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), translator);
        cleanUp();
        jdbc.update("INSERT INTO erp.product (sku, name, unit_price) VALUES (?, 'Produit de test', 1000)", SKU);
        jdbc.update("INSERT INTO erp.inventory (sku, available, initial_quantity) VALUES (?, ?, ?)", SKU, STOCK, STOCK);
    }

    @AfterEach
    void cleanUp() {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM erp.invoice WHERE order_ref LIKE 'TEST-RACE-%'");
        jdbc.update("DELETE FROM erp.erp_order_line WHERE order_ref LIKE 'TEST-RACE-%'");
        jdbc.update("DELETE FROM erp.erp_order WHERE ref LIKE 'TEST-RACE-%'");
        jdbc.update("DELETE FROM erp.inventory WHERE sku = ?", SKU);
        jdbc.update("DELETE FROM erp.product WHERE sku = ?", SKU);
    }

    @Test
    void vingtAcheteursSimultanesPourTroisArticles() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErpAck>> results = new ArrayList<>();

        for (int i = 1; i <= BUYERS; i++) {
            String xml = translator.toErpXml(order("TEST-RACE-" + i));
            results.add(pool.submit(() -> {
                go.await();                 // tous les threads partent au même instant
                return erp.receiveOrderXml(xml);
            }));
        }
        go.countDown();

        int reserved = 0;
        for (Future<ErpAck> f : results) {
            if (f.get().reserved()) {
                reserved++;
            }
        }
        pool.shutdown();

        Integer available = jdbc.queryForObject("SELECT available FROM erp.inventory WHERE sku = ?", Integer.class, SKU);
        Integer invoices = jdbc.queryForObject("SELECT count(*) FROM erp.invoice WHERE order_ref LIKE 'TEST-RACE-%'", Integer.class);

        assertThat(reserved).isEqualTo(STOCK);
        assertThat(available).isZero();
        assertThat(invoices).isEqualTo(STOCK);
    }

    private static CanonicalOrder order(String ref) {
        CanonicalOrder o = new CanonicalOrder();
        o.setOrderId(ref);
        o.setCustomerId("CLI-TEST");
        o.getItems().add(new OrderLine(SKU, 1, new BigDecimal("1000")));
        o.setTotal(new BigDecimal("1000"));
        return o;
    }

    private static boolean isReachable(DriverManagerDataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
