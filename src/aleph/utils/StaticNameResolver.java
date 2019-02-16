package aleph.utils;

import io.netty.resolver.InetNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.DomainNameMapping;
import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public class StaticNameResolver extends InetNameResolver {

    public final static InetAddress UNKNOWN_HOST_MARKER;

    static {
        // well... this is actually a dirty workaround against
        // the fact that DomainMapping requires a default value
        // (to be returned when nothing was found) to be of the
        // save class as a result, NULL doesn't work too
        // so we use here a reserved IP address hoping that
        // no one will ever want to resolve any host name into this
        InetAddress marker = null;
        try {
            marker = InetAddress.getByName("240.0.0.0");
        } catch (UnknownHostException e) {
            // should never happen
        } finally {
            UNKNOWN_HOST_MARKER = marker;
        }
    }

    private final DomainNameMapping<InetAddress> hosts;

    public StaticNameResolver(EventExecutor executor, DomainNameMapping<InetAddress> hosts) {
        super(executor);
        this.hosts = hosts;
    }

    @Override
    protected void doResolve(String inetHost, Promise<InetAddress> promise) {
        InetAddress address = hosts.map(inetHost);
        if (address.equals(UNKNOWN_HOST_MARKER)) {
            promise.setFailure(new UnknownHostException());
        } else {
            promise.setSuccess(address);
        }
    }

    @Override
    protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
        InetAddress address = hosts.map(inetHost);
        if (address.equals(UNKNOWN_HOST_MARKER)) {
            promise.setFailure(new UnknownHostException());
        } else {
            promise.setSuccess(Arrays.asList(address));
        }
    }

}
