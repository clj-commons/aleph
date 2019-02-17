package aleph.utils;

public class UnrecognizedHostException extends RuntimeException {

    public UnrecognizedHostException() { }

    public UnrecognizedHostException(String message) {
        super(message);
    }

    public UnrecognizedHostException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public UnrecognizedHostException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

}
