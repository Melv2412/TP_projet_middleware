package com.sidam.connect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SIDAM CONNECT : bus d'intégration entre le Store (JSON), l'ERP legacy (XML/CSV)
 * et l'application de livraison (JSON).
 */
@SpringBootApplication
public class IntegrationBusApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationBusApplication.class, args);
    }
}
