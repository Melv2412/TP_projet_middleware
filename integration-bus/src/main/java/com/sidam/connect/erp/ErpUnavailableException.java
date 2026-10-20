package com.sidam.connect.erp;

/** Erreur technique : l'ERP legacy ne répond pas. Elle déclenche le Retry puis la DLQ. */
public class ErpUnavailableException extends RuntimeException {

    public ErpUnavailableException(String message) {
        super(message);
    }
}
