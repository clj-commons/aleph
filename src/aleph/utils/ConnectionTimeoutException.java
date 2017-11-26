package aleph.utils;

import java.util.concurrent.TimeoutException;

public class ConnectionTimeoutException extends TimeoutException {

    public ConnectionTimeoutException() { }
    
    public ConnectionTimeoutException(String message) {
        super(message);
    }
    
    public ConnectionTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public ConnectionTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
    
}
