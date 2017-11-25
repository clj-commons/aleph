package aleph.utils;

import java.util.concurrent.TimeoutException;

public class ConnectionTimeoutException extends TimeoutException {

    public ConnectionTimeoutException() { }
    
    public ConnectionTimeoutException(String message) {
        super(message);
    }

}
