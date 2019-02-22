package aleph.utils;

import javax.net.ssl.*;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AsyncMapping;
import io.netty.util.DomainNameMapping;
import io.netty.util.Mapping;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;

import java.security.cert.CertificateException;
import java.util.Collections;

/**
 * Quick and dirty hack to avoid necessity for default SSL context when creating
 * new SniHandler. Uses SNI matcher from javax.net package over SSL context spawned
 * using self signed cert (hah), theoretically it should never be invoked tho'.
 *
 * Hope we can update Netty's implementation to be more flexible on this and
 * throw the following class away as soon as possible.
 */
public class StrictSniHandler extends AbstractSniHandler<Option<SslContext>>  {
    private static final SslContext INSECURE_CONTEXT_INSTANCE;

    static {
        SslContext insecure;
        try {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            insecure = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } catch(CertificateException e) {
            insecure = null;
        } catch(SSLException e) {
            insecure = null;
        }
        INSECURE_CONTEXT_INSTANCE = insecure;
    }

    private static final int HOSTNAME_TYPE = 0;

    private static final SNIMatcher REJECT_ALL = new SNIMatcher(HOSTNAME_TYPE) {
        @Override
        public boolean matches(SNIServerName sniServerName) {
            return false;
        }
    };

    private static final Selection EMPTY_SELECTION = new Selection(null, null);

    protected final AsyncMapping<String, Option<SslContext>> mapping;

    private volatile Selection selection = EMPTY_SELECTION;

    /**
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    public StrictSniHandler(Mapping<String, Option<SslContext>> mapping) {
        this(new AsyncMappingAdapter(mapping));
    }

    /**
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    public StrictSniHandler(DomainNameMapping<Option<SslContext>> mapping) {
        this((Mapping<String, Option<SslContext>>) mapping);
    }

    /**
     * @param mapping the mapping of domain name to {@link SslContext}
     */
    @SuppressWarnings("unchecked")
    public StrictSniHandler(AsyncMapping<String, Option<SslContext>> mapping) {
        this.mapping = (AsyncMapping<String, Option<SslContext>>) ObjectUtil.checkNotNull(mapping, "mapping");
    }

    /**
     * @return the selected hostname
     */
    public String hostname() {
        return selection.hostname;
    }

    /**
     * @return the selected {@link SslContext}
     */
    public SslContext sslContext() {
        return selection.context;
    }

    /**
     * @see AsyncMapping#map(Object, Promise)
     */
    @Override
    protected Future<Option<SslContext>> lookup(ChannelHandlerContext ctx, String hostname) throws Exception {
        return mapping.map(hostname, ctx.executor().<Option<SslContext>>newPromise());
    }

    @Override
    protected final void onLookupComplete(ChannelHandlerContext ctx,
                                          String hostname, Future<Option<SslContext>> future) throws Exception {
        if (!future.isSuccess()) {
            final Throwable cause = future.cause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new DecoderException("failed to get the SslContext for " + hostname, cause);
        }

        Option<SslContext> maybeSslContext = future.getNow();
        if (!maybeSslContext.isSome()) {
            replaceRejectAllHandler(ctx);
            selection = EMPTY_SELECTION;
        } else {
            SslContext sslContext = maybeSslContext.get();
            selection = new Selection(sslContext, hostname);
            try {
                replaceHandler(ctx, hostname, sslContext);
            } catch (Throwable cause) {
                selection = EMPTY_SELECTION;
                PlatformDependent.throwException(cause);
            }
        }
    }

    protected void replaceRejectAllHandler(ChannelHandlerContext ctx) throws Exception {
        SslHandler sslHandler = null;
        try {
            sslHandler = INSECURE_CONTEXT_INSTANCE.newHandler(ctx.alloc());
            SSLParameters params = sslHandler.engine().getSSLParameters();
            params.setSNIMatchers(Collections.singletonList(REJECT_ALL));
            sslHandler.engine().setSSLParameters(params);
            ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
            sslHandler = null;
        } finally {
            if (sslHandler != null) {
                ReferenceCountUtil.safeRelease(sslHandler.engine());
            }
        }
    }

    protected void replaceHandler(ChannelHandlerContext ctx, String hostname, SslContext sslContext) throws Exception {
        SslHandler sslHandler = null;
        try {
            sslHandler = sslContext.newHandler(ctx.alloc());
            ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
            sslHandler = null;
        } finally {
            if (sslHandler != null) {
                ReferenceCountUtil.safeRelease(sslHandler.engine());
            }
        }
    }

    private static final class AsyncMappingAdapter implements AsyncMapping<String, Option<SslContext>> {
        private final Mapping<String, Option<SslContext>> mapping;

        private AsyncMappingAdapter(Mapping<String, Option<SslContext>> mapping) {
            this.mapping = ObjectUtil.checkNotNull(mapping, "mapping");
        }

        @Override
        public Future<Option<SslContext>> map(String input, Promise<Option<SslContext>> promise) {
            final Option<SslContext> context;
            try {
                context = mapping.map(input);
            } catch (Throwable cause) {
                return promise.setFailure(cause);
            }
            return promise.setSuccess(context);
        }
    }

    private static final class Selection {
        final SslContext context;
        final String hostname;

        Selection(SslContext context, String hostname) {
            this.context = context;
            this.hostname = hostname;
        }
    }

}
