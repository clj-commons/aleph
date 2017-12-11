package aleph.utils;

import java.util.concurrent.TimeoutException;

public class ReadTimeoutException extends TimeoutException {

    public ReadTimeoutException() { }
    
    public ReadTimeoutException(String message) {
        super(message);
    }

    public ReadTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }
    
    public ReadTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
    
}
