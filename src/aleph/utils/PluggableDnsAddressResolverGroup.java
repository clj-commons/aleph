package aleph.utils;

import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.NameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.util.concurrent.EventExecutor;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static io.netty.resolver.dns.DnsServerAddressStreamProviders.platformDefault;

// well...
// this is just a simple workaround of how to create AddressResolverGroup from
// the given instance of DNS-based NameResolver
// there is a https://github.com/netty/netty/blob/netty-4.1.17.Final/resolver-dns/src/main/java/io/netty/resolver/dns/DnsAddressResolverGroup.java
// but it does not provide enough flexibility as it uses DnsNameResolverBuilder internally,
// shadowing pretty much all settings that DNS resolver exposes
public class PluggableDnsAddressResolverGroup extends DnsAddressResolverGroup {

    public DnsNameResolverBuilder resolverBuilder;

    public PluggableDnsAddressResolverGroup(DnsNameResolverBuilder builder) {
        // we don't need none of them, all information is already handled within the builder
        super(NioDatagramChannel.class, platformDefault());
        this.resolverBuilder = builder;
    }
    
    @Override
    protected NameResolver<InetAddress> newNameResolver(EventLoop eventLoop,
                                                        ChannelFactory<? extends DatagramChannel> channelFactory,
                                                        DnsServerAddressStreamProvider nameServerProvider)
            throws Exception {
        // ignoring all information from params on purpose
        // assuming that our builder is "ready to go"
        return this.resolverBuilder.build();
    }
}
