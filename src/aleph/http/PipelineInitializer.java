package aleph.http;

import clojure.lang.IFn;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

public class PipelineInitializer extends ChannelInitializer<Channel> {
    
    private final IFn pipelineBuilderFn;
    
    public PipelineInitializer(IFn pipelineBuilderFn) {
        this.pipelineBuilderFn = pipelineBuilderFn;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        pipelineBuilderFn.invoke(ch.pipeline());
    }
}
