package aleph.utils;

import io.netty.util.internal.StringUtil;
import io.netty.util.Mapping;

import java.net.IDN;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.StringUtil.commonSuffixOfLength;

/**
 * Code ported from:
 *
 * https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/DomainNameMapping.java
 *
 * The only difference is that it does not operation with **default** value.
 * Mapping.map operation just throws UnknownHostException when nothing was found.
 * The one could also write a builder and immutable version of the map based on
 * LinkedHashMap (see how Netty does this). But taking into account constrained
 * usage pattern... we're good to go with a simpler version. In case it causes
 * any troubles we can reconsider this any time.
 *
 * Usage in the project:
 * - simplified StaticNameResolver implementation (no need for dummy default value)
 * - makes SniHandler to throw DecoderException and short-circuit SSL handshake
 */
public class DomainNameMapping<V> implements Mapping<String, V> {

    final V defaultValue;
    private final Map<String, V> map;
    private final Map<String, V> unmodifiableMap;

    public DomainNameMapping() {
        this(4);
    }

    public DomainNameMapping(int initialCapacity) {
        this(new LinkedHashMap<String, V>(initialCapacity), null);
    }

    public DomainNameMapping(int initialCapacity, V defaultValue) {
        this(new LinkedHashMap<String, V>(initialCapacity), defaultValue);
    }

    DomainNameMapping(Map<String, V> map, V defaultValue) {
        this.defaultValue = defaultValue;
        this.map = map;
        unmodifiableMap = map != null ? Collections.unmodifiableMap(map) : null;
    }

    public DomainNameMapping<V> add(String hostname, V output) {
        map.put(normalizeHostname(checkNotNull(hostname, "hostname")), checkNotNull(output, "output"));
        return this;
    }

    /**
     * Simple function to match <a href="http://en.wikipedia.org/wiki/Wildcard_DNS_record">DNS wildcard</a>.
     */
    static boolean matches(String template, String hostName) {
        if (template.startsWith("*.")) {
            return template.regionMatches(2, hostName, 0, hostName.length())
                    || commonSuffixOfLength(hostName, template, template.length() - 1);
        }
        return template.equals(hostName);
    }

    /**
     * IDNA ASCII conversion and case normalization
     */
    static String normalizeHostname(String hostname) {
        if (needsNormalization(hostname)) {
            hostname = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED);
        }
        return hostname.toLowerCase(Locale.US);
    }

    private static boolean needsNormalization(String hostname) {
        final int length = hostname.length();
        for (int i = 0; i < length; i++) {
            int c = hostname.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        return false;
    }

    @Override
    public V map(String hostname) {
        if (hostname != null) {
            hostname = normalizeHostname(hostname);

            for (Map.Entry<String, V> entry : map.entrySet()) {
                if (matches(entry.getKey(), hostname)) {
                    return entry.getValue();
                }
            }
        }

        if (defaultValue != null) {
            return defaultValue;
        }

        throw new UnrecognizedHostException("unrecognized host '"  + hostname + "'");
    }

    /**
     * Returns a read-only {@link Map} of the domain mapping patterns and their associated value objects.
     */
    public Map<String, V> asMap() {
        return unmodifiableMap;
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) + "(map: " + map + ')';
    }
}
