package aleph.utils;

import java.util.concurrent.TimeoutException;

public class ProxyConnectionTimeoutException extends TimeoutException {

    public ProxyConnectionTimeoutException() { }
    
    public ProxyConnectionTimeoutException(String message) {
        super(message);
    }
    
    public ProxyConnectionTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public ProxyConnectionTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
    
}
