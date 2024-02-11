package aleph.utils;

import java.util.concurrent.CancellationException;

public class RequestCancellationException extends CancellationException {

    public RequestCancellationException() { }

    public RequestCancellationException(String message) {
        super(message);
    }

    public RequestCancellationException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public RequestCancellationException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

}
