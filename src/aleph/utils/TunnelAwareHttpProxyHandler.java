package aleph.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class TunnelAwareHttpProxyHandler extends ProxyHandler {

    private static final String PROTOCOL = "http";
    private static final String AUTH_BASIC = "basic";
    private static final String AUTH_NONE = "none";

    private final HttpClientCodec codec = new HttpClientCodec();
    private String username;
    private String password;
    private CharSequence authorization;
    private boolean useTunnel;
    private HttpResponseStatus status;
    private HttpHeaders headers;

    public TunnelAwareHttpProxyHandler(SocketAddress proxyAddress) {
        super(proxyAddress);
        this.username = null;
        this.password = null;
        this.authorization = null;
        this.useTunnel = false;
        this.headers = null;
    }
    
    public void setAuthInfo(String username, String password) {
        if (username != null) {        
            this.username = username;
            this.password = password;
            updateAuthorization();

            this.useTunnel = true;
        }
    }

    public void updateAuthorization() {
        String info = password == null ? username : username + ':' + password;
        ByteBuf auth = Unpooled.copiedBuffer(info, CharsetUtil.UTF_8);
        ByteBuf authBase64 = Base64.encode(auth, false);

        authorization = new AsciiString("Basic " + authBase64.toString(CharsetUtil.US_ASCII));

        auth.release();
        authBase64.release();
    }

    public void setHeaders(HttpHeaders headers) {
        if (headers != null) {
            this.headers = headers;
            this.useTunnel = true;
        }
    }

    public void setUseTunnel(boolean use) {
        this.useTunnel = use;
    }
    
    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public String authScheme() {
        return authorization != null ? AUTH_BASIC : AUTH_NONE;
    }

    @Override
    protected void addCodec(ChannelHandlerContext context) throws Exception {
        ChannelPipeline p = context.pipeline();
        p.addBefore(context.name(), null, codec);
    }

    @Override
    protected void removeEncoder(ChannelHandlerContext context) throws Exception {
        codec.removeOutboundHandler();
    }

    @Override
    protected void removeDecoder(ChannelHandlerContext context) throws Exception {
        codec.removeInboundHandler();
    }

    @Override
    protected Object newInitialMessage(ChannelHandlerContext context) throws Exception {
        if (!useTunnel) {
            return null;
        }
        
        InetSocketAddress raddr = destinationAddress();
        final String host = NetUtil.toSocketAddressString(raddr);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                                                         HttpMethod.CONNECT,
                                                         host,
                                                         Unpooled.EMPTY_BUFFER,
                                                         false);

        req.headers().set(HttpHeaderNames.HOST, host);

        if (authorization != null) {
            req.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, authorization);
        }

        if (headers != null) {
            req.headers().add(headers);
        }

        return req;
    }

    // xxx: :thinking: should we wait for the response here when not using CONNECT?
    // xxx: exceptionMessage: port?
    @Override
    protected boolean handleResponse(ChannelHandlerContext ctx, Object response) throws Exception {
        if (response instanceof HttpResponse) {
            if (status != null) {
                throw new ProxyConnectException("too many responses");
            }
            status = ((HttpResponse) response).status();
        }

        boolean finished = response instanceof LastHttpContent;
        if (finished) {
            if (status == null) {
                throw new ProxyConnectException("missing response");
            }
            if (status.code() != 200) {
                throw new ProxyConnectException("status: " + status);
            }
        }

        return finished;
    }
}

