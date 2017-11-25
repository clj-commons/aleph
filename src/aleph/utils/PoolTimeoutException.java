package aleph.utils;

import java.util.concurrent.TimeoutException;

public class PoolTimeoutException extends TimeoutException {

    public PoolTimeoutException() { }
    
    public PoolTimeoutException(String message) {
        super(message);
    }

    public PoolTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public PoolTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
    
}
