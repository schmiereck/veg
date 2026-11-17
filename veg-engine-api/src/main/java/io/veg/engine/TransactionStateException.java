package io.veg.engine;

/**
 * Thrown when an operation is invoked on a closed/committed/rolled-back transaction
 * or with an invalid/superseded savepoint.
 */
public class TransactionStateException extends VegException {

    public TransactionStateException(String message) {
        super(message);
    }
}
