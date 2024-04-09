package aleph.http;

import io.netty.handler.codec.compression.BrotliOptions;
import io.netty.handler.codec.compression.CompressionOptions;
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

    private static boolean containsInstanceOf(CompressionOptions[] options, Class optionClass) {
        for (CompressionOptions o : options) {
            // NOTE: Can't use optionClass.instanceOf(o) because GzipOptions inherits from
            // DeflateOptions which yields false positives.
            if (optionClass == o.getClass()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasBrotli(CompressionOptions[] options) {
        return containsInstanceOf(options, BrotliOptions.class);
    }

    public static boolean hasZstd(CompressionOptions[] options) {
        return containsInstanceOf(options, ZstdOptions.class);
    }

    public static boolean hasSnappy(CompressionOptions[] options) {
        return containsInstanceOf(options, SnappyOptions.class);
    }

    public static boolean hasGzip(CompressionOptions[] options) {
        return containsInstanceOf(options, GzipOptions.class);
    }

    public static boolean hasDeflate(CompressionOptions[] options) {
        return containsInstanceOf(options, DeflateOptions.class);
    }
}
