package com.fragpicker.integration.media;

import org.apache.http.conn.DnsResolver;
import java.net.*;
import java.util.Set;

/** Validated DNS results are used directly by the HTTP socket connector, preventing DNS re-resolution. */
public class PublicNetworkPolicy implements DnsResolver {
    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        return requirePublic(addresses);
    }

    static InetAddress[] requirePublic(InetAddress[] addresses) throws UnknownHostException {
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("Media DNS returned no addresses");
        for (var address : addresses) if (!isPublic(address)) throw new RejectedAddress();
        return addresses;
    }

    static void validateUri(URI uri) {
        try {
            if (uri == null || !Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.toASCIIString().length() > 8192 || (uri.getPort() != -1
                    && uri.getPort() != ("https".equals(uri.getScheme()) ? 443 : 80))) throw new IllegalArgumentException();
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.contains(":")) {
                if (!isPublic(InetAddress.getByName(host))) throw new IllegalArgumentException();
            } else if (host.matches("[0-9.]+")) {
                if (!isPublic(InetAddress.getByName(host))) throw new IllegalArgumentException();
            } else if (!host.contains(".") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".")) throw new IllegalArgumentException();
        } catch (Exception rejected) { throw new MediaDownloadFailure(MediaDownloadFailure.Code.TARGET_REJECTED, false); }
    }

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = b[0] & 255, second = b[1] & 255;
        if (b.length == 4) {
            int third = b[2] & 255;
            return !(first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254) || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && ((second == 0 && (third == 0 || third == 2))
                    || (second == 88 && third == 99) || second == 168))
                    || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113));
        }
        // Only IPv6 global unicast; also exclude documentation, special/transition allocations.
        if (b.length != 16 || (first & 0xe0) != 0x20) return false;
        if (first == 0x3f && second == 0xff && (b[2] & 0xf0) == 0) return false; // Documentation 3fff::/20.
        if (first == 0x20 && second == 0x02) return false; // 6to4 embeds an IPv4 destination.
        if (first == 0x20 && second == 0x01) {
            int subnet = ((b[2] & 255) << 8) | (b[3] & 255);
            if (subnet <= 0x01ff || subnet == 0x0db8) return false;
        }
        return true;
    }

    static class RejectedAddress extends UnknownHostException {
        RejectedAddress() { super("Media DNS resolved to a non-public address"); }
    }
}
