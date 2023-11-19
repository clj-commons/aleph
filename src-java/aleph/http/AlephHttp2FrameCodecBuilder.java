package aleph.http;

import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.http2.*;

/**
 * This class is essentially Http2FrameCodecBuilder, with the following changes:
 * - its constructors take several compression parameters
 * - if compressing, it wraps the encoder in a CompressorHttp2ConnectionEncoder
 *   and the frame listener in a DelegatingDecompressorFrameListener
 */
public class AlephHttp2FrameCodecBuilder extends Http2FrameCodecBuilder {
    private boolean compressing = false;
    private CompressionOptions[] compressionOpts;

    public AlephHttp2FrameCodecBuilder(boolean server) {
        // Can't call super() because it's only package-visible, so the
        // below is copied from the Http2FrameCodecBuilder ctor. Let's hope
        // parent doesn't change.
        server(server);
        gracefulShutdownTimeoutMillis(0);
    }

    public AlephHttp2FrameCodecBuilder setCompression(boolean compressing, CompressionOptions... compressionOpts) {
        this.compressing = compressing;
        this.compressionOpts = compressionOpts;
        return this;
    }

    private CompressorHttp2ConnectionEncoder compressorEncoder(Http2ConnectionEncoder encoder) {
        if(this.compressionOpts != null) {
            return new CompressorHttp2ConnectionEncoder(encoder, this.compressionOpts);
        }
        return new CompressorHttp2ConnectionEncoder(encoder);
    }

    public Http2FrameCodec build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                 Http2Settings initialSettings) {
        if (compressing) {
            // compressing - wrap encoder
            CompressorHttp2ConnectionEncoder compressingEncoder = compressorEncoder(encoder);

            // build codec
            Http2FrameCodec codec = super.build(decoder, compressingEncoder, initialSettings);

            // decompressing - update frame listener after building codec,
            // because the Http2FrameCodec.FrameListener class is private
            Http2Connection http2Connection = codec.connection();
            Http2FrameListener listener = codec.decoder().frameListener();
            codec.decoder().frameListener(
                    new DelegatingDecompressorFrameListener(http2Connection, listener));

            return codec;
        } else {
            return super.build(decoder, encoder, initialSettings);
        }
    }
}
