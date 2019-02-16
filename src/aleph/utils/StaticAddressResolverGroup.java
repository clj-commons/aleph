package aleph.utils;

import io.netty.util.DomainNameMapping;
import io.netty.util.concurrent.EventExecutor;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class StaticAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final DomainNameMapping<InetAddress> hosts;

    public StaticAddressResolverGroup(DomainNameMapping<InetAddress> hosts) {
        this.hosts = hosts;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        return new InetSocketAddressResolver(executor, new StaticNameResolver(executor, hosts));
    }
}

