(ns aleph.dns
  (:refer-clojure :exclude [resolve])
  (:require
    [aleph.netty :as netty])
  (:import
    [java.net
     InetSocketAddress]
    [io.netty.channel
     EventLoop
     EventLoopGroup]
    [io.netty.channel.nio
     NioEventLoopGroup]
    [io.netty.channel.socket.nio
     NioDatagramChannel]
    [io.netty.resolver.dns
     DnsCache
     DnsServerAddresses
     DnsAddressResolverGroup
     DnsNameResolver
     DnsNameResolverContext
     DnsNameResolverBuilder]
    [io.netty.resolver
     AddressResolver
     DefaultAddressResolverGroup
     AddressResolverGroup]))

(defn default-dns-hostnames []
  (->> (DnsServerAddresses/defaultAddressList)
    (map #(.getHostName ^InetSocketAddress %))))

(defn noop-dns-cache []
  (reify DnsCache
    (clear [_])
    (clear [_ hostname] false)
    (get [_ hostname] nil)
    (cache [_ hostname address ttl loop])
    (cache [_ hostname cause loop])))

(defn resolver-group
  [{:keys [dns-hostnames
           resolver-builder-transform]
    :or {dns-hostnames (default-dns-hostnames)
         resolver-builder-transform identity}}]
  (proxy [DnsAddressResolverGroup]
    [NioDatagramChannel
     (->> dns-hostnames
       ^Iterable (mapv #(InetSocketAddress. ^String % 53))
       DnsServerAddresses/sequential)]
    (newResolver [event-loop channel-factory local-address dns-server-addresses]
      (-> (DnsNameResolverBuilder. event-loop)
        (.channelFactory channel-factory)
        (.localAddress local-address)
        (.nameServerAddresses dns-server-addresses)
        ^DnsNameResolverBuilder (resolver-builder-transform)
        .build
        .asAddressResolver))))

(defn resolver [^AddressResolverGroup resolver-group ^EventLoopGroup event-loop-group]
  (.getResolver resolver-group (.next event-loop-group)))

(defn resolve [^AddressResolver resolver ^String hostname]
  (.resolve resolver (InetSocketAddress/createUnresolved hostname 0)))
