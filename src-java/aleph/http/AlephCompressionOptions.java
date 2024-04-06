package aleph.http;

import io.netty.handler.codec.compression.BrotliOptions;
import io.netty.handler.codec.compression.DeflateOptions;
import io.netty.handler.codec.compression.GzipOptions;
import io.netty.handler.codec.compression.SnappyOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.compression.ZstdOptions;

/**
 * {@link AlephCompressionOptions} exists because the Clojure compiler cannot
 * distinguish between static fields and static methods without reflection.
 *
 * This is a problem when using Netty's StandardCompressionOptions, because
 * reflection triggers a load of all the methods  referencing optional classes,
 * which may not exist in the classpath, resulting in a ClassNotFoundException.
 */
public class AlephCompressionOptions {
    private AlephCompressionOptions() {
        // Prevent outside initialization
    }

    public static BrotliOptions brotli() {
        return StandardCompressionOptions.brotli();
    }

    public static ZstdOptions zstd() {
        return StandardCompressionOptions.zstd();
    }

    public static SnappyOptions snappy() {
        return StandardCompressionOptions.snappy();
    }

    public static GzipOptions gzip() {
        return StandardCompressionOptions.gzip();
    }

    public static DeflateOptions deflate() {
        return StandardCompressionOptions.deflate();
    }
}
