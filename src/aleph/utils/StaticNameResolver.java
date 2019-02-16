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

    public final static InetAddress UNKNOWN_HOST_MARKER = (InetAddress) new Object();

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
