package aleph.http;

import clojure.lang.IFn;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

/**
 * An ApplicationProtocolNegotiationHandler that will call the supplied
 * Clojure IFn when the TLS handshake is complete. The handler will finish
 * setting up the pipeline for the negotiated protocol.
 */
public class ApnHandler extends ApplicationProtocolNegotiationHandler {

    private final IFn pipelineBuilderFn;

    public ApnHandler(IFn pipelineBuilderFn, String fallbackProtocol) {
        super(fallbackProtocol);
        this.pipelineBuilderFn = pipelineBuilderFn;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
        pipelineBuilderFn.invoke(ctx.pipeline(), protocol);
    }
}
