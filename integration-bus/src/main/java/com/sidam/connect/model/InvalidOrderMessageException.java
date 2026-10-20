package com.sidam.connect.model;

/** Message lisible mais incomplet ou incohérent : envoyé en DLQ sans nouvelle tentative. */
public class InvalidOrderMessageException extends RuntimeException {

    public InvalidOrderMessageException(String message) {
        super(message);
    }
}
