package aleph.utils;

import io.netty.resolver.InetNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.DomainNameMapping;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.net.UnknownHostException;

public class StaticNameResolver extends InetNameResolver {

    private final DomainNameMapping<Option<InetAddress>> hosts;

    public StaticNameResolver(EventExecutor executor, DomainNameMapping<Option<InetAddress>> hosts) {
        super(executor);
        this.hosts = hosts;
    }

    @Override
    protected void doResolve(String inetHost, Promise<InetAddress> promise) {
        Option<InetAddress> maybeResolved = hosts.map(inetHost);
        if(maybeResolved.isSome()) {
            promise.setSuccess(maybeResolved.get());
        } else {
            promise.setFailure(new UnknownHostException(inetHost));
        }
    }

    @Override
    protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
        Option<InetAddress> maybeResolved = hosts.map(inetHost);
        if(maybeResolved.isSome()) {
            promise.setSuccess(Collections.singletonList(maybeResolved.get()));
        } else {
            promise.setFailure(new UnknownHostException(inetHost));
        }
    }

}
