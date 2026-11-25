package ai.myrmec.engine._system.exception;

public class CapacityException extends RuntimeException {

    public CapacityException(String message) {
        super(message);
    }

    public CapacityException(String message, Throwable cause) {
        super(message, cause);
    }

}
