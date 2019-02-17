package aleph.utils;

import io.netty.resolver.InetNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public class StaticNameResolver extends InetNameResolver {

    private final DomainNameMapping<InetAddress> hosts;

    public StaticNameResolver(EventExecutor executor, DomainNameMapping<InetAddress> hosts) {
        super(executor);
        this.hosts = hosts;
    }

    @Override
    protected void doResolve(String inetHost, Promise<InetAddress> promise) {
        try {
            promise.setSuccess(hosts.map(inetHost));
        } catch (UnknownHostException e) {
            promise.setFailure(e);
        }
    }

    @Override
    protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
        try {
            promise.setSuccess(Arrays.asList(hosts.map(inetHost)));
        } catch (UnknownHostException e) {
            promise.setFailure(e);
        }
    }

}
