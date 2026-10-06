package com.fragpicker.integration.media;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PublicNetworkPolicyTest {
    @Test void rejectsPrivateReservedDocumentationAndTransitionAddresses() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.1.2.3", "127.0.0.1", "100.64.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.1",
                "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.2", "203.0.113.8", "224.1.1.1", "240.1.1.1",
                "::", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2001:db8::1", "2002:7f00:1::1", "2001::1", "3fff::1")) {
            assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        assertThatThrownBy(() -> PublicNetworkPolicy.requirePublic(new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")}))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test void rejectsUnsafeSyntaxPortsCredentialFragmentsAndNumericLoopbackAliases() {
        for (String uri : List.of("file:///tmp/a", "http://localhost/a", "http://2130706433/a", "http://127.1/a", "http://[::1]/a",
                "http://media.example:8080/a", "https://u:p@media.example/a", "https://media.example/a#fragment", "http://media.local/a")) {
            assertThatThrownBy(() -> PublicNetworkPolicy.validateUri(URI.create(uri))).isInstanceOf(MediaDownloadFailure.class);
        }
        assertThatCode(() -> PublicNetworkPolicy.validateUri(URI.create("https://cdn.example.com/a?sign=abc%2F123"))).doesNotThrowAnyException();
    }
}
