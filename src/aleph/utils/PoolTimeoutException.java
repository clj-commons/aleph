package aleph.utils;

import java.util.concurrent.TimeoutException;

public class PoolTimeoutException extends TimeoutException {

    public PoolTimeoutException() { }
    
    public PoolTimeoutException(String message) {
        super(message);
    }

}
