package aleph.utils;

import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.NameResolver;
import io.netty.util.concurrent.EventExecutor;

import java.net.InetAddress;
import java.net.InetSocketAddress;

// well...
// this is just a simple workaround of how to create AddressResolverGroup from
// the given instance of DNS-based NameResolver
// there is a https://github.com/netty/netty/blob/netty-4.1.17.Final/resolver-dns/src/main/java/io/netty/resolver/dns/DnsAddressResolverGroup.java
// but it does not provide enough flexibility as it uses DnsNameResolverBuilder internally,
// shadowing pretty much all settings that DNS resolver exposes
public class DnsAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    protected NameResolver<InetAddress> resolver;
    
    public DnsAddressResolverGroup(NameResolver<InetAddress> resolver) {
        this.resolver = resolver;
    }

    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new InetSocketAddressResolver((EventLoop) executor, resolver);
    }
}

