package aleph.utils;

import java.util.concurrent.TimeoutException;

public class WebSocketHandshakeTimeoutException extends TimeoutException {

    public WebSocketHandshakeTimeoutException() { }

    public WebSocketHandshakeTimeoutException(String message) {
        super(message);
    }

    public WebSocketHandshakeTimeoutException(Throwable cause) {
        super(cause.getMessage());
        initCause(cause);
    }

    public WebSocketHandshakeTimeoutException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

}
