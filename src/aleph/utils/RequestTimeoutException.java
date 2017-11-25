package aleph.utils;

import java.util.concurrent.TimeoutException;

public class RequestTimeoutException extends TimeoutException {

    public RequestTimeoutException() { }
    
    public RequestTimeoutException(String message) {
        super(message);
    }

}
