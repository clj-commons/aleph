package aleph.utils;

import java.util.concurrent.TimeoutException;

public class RequestTimeoutException extends TimeoutException {

    public RequestTimeoutException() { }
    
    public RequestTimeoutException(String message) {
        super(message);
    }

    public RequestTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }
    
    public RequestTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
    
}
