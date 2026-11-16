package com.justdance.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class LinkTargetTest {
    @Test
    public void cloudBaseUrlFromSiteAndChannel() {
        assertEquals("https://jd.example.com/c/luis", LinkTarget.cloudBaseUrl("jd.example.com", "luis"));
        assertEquals("https://jd.example.com/c/luis", LinkTarget.cloudBaseUrl(" https://JD.example.com/ ", " @Luis "));
        assertEquals("https://jd.example.com:8443/c/some_name1", LinkTarget.cloudBaseUrl("https://jd.example.com:8443", "#some_name1"));
        assertEquals("https://jd.example.com/c/luis", LinkTarget.cloudBaseUrl("https://jd.example.com:443", "luis"));
    }

    @Test
    public void cloudBaseUrlFromPastedChannelLink() {
        assertEquals("https://jd.example.com/c/luis", LinkTarget.cloudBaseUrl("https://jd.example.com/c/luis", ""));
        assertEquals("https://jd.example.com/c/luis", LinkTarget.cloudBaseUrl("jd.example.com/c/Luis/dashboard?x=1", null));
        // The channel field wins over the link.
        assertEquals("https://jd.example.com/c/friend", LinkTarget.cloudBaseUrl("https://jd.example.com/c/luis/", "friend"));
    }

    @Test
    public void cloudBaseUrlRefusesAnythingButHttps() {
        assertRefused("http://jd.example.com", "luis");
        assertRefused("HTTP://jd.example.com/c/luis", "");
        assertRefused("ftp://jd.example.com", "luis");
        assertRefused("https://user:pass@jd.example.com", "luis");
        assertRefused("", "luis");
        assertRefused("https://", "luis");
    }

    @Test
    public void cloudBaseUrlRefusesBadChannels() {
        assertRefused("jd.example.com", "");
        assertRefused("jd.example.com/dashboard", "");
        assertRefused("jd.example.com", "luis/../admin");
        assertRefused("jd.example.com", "a name");
        assertRefused("jd.example.com", "abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    public void cloudPartsSplitsASavedLink() {
        assertArrayEquals(new String[]{"https://jd.example.com", "luis"}, LinkTarget.cloudParts("https://jd.example.com/c/luis"));
        assertArrayEquals(new String[]{"", ""}, LinkTarget.cloudParts("http://192.168.1.23:3000"));
        assertArrayEquals(new String[]{"", ""}, LinkTarget.cloudParts(null));
    }

    @Test
    public void privateIpv4Ranges() {
        assertTrue(LinkTarget.isPrivateIpv4("10.0.0.5"));
        assertTrue(LinkTarget.isPrivateIpv4("172.16.0.1"));
        assertTrue(LinkTarget.isPrivateIpv4("172.31.255.254"));
        assertTrue(LinkTarget.isPrivateIpv4("192.168.1.23"));
        assertFalse(LinkTarget.isPrivateIpv4("172.32.0.1"));
        assertFalse(LinkTarget.isPrivateIpv4("192.169.1.1"));
        assertFalse(LinkTarget.isPrivateIpv4("8.8.8.8"));
        assertFalse(LinkTarget.isPrivateIpv4("100.64.0.1"));
        assertFalse(LinkTarget.isPrivateIpv4("127.0.0.1"));
        assertFalse(LinkTarget.isPrivateIpv4("10.0.0.256"));
        assertFalse(LinkTarget.isPrivateIpv4("10.0.0"));
        assertFalse(LinkTarget.isPrivateIpv4("10.0.0.5.evil.example"));
        assertFalse(LinkTarget.isPrivateIpv4("evil.example"));
        assertFalse(LinkTarget.isPrivateIpv4(null));
    }

    @Test
    public void discoveryOnlyAcceptsTheSendersOwnPrivateAddress() {
        assertEquals("http://192.168.1.23:3000", LinkTarget.discoveredBaseUrl("http://192.168.1.23:3000", "192.168.1.23"));
        assertEquals("http://192.168.1.23:3001", LinkTarget.discoveredBaseUrl("http://192.168.1.23:3001/", "192.168.1.23"));
        // Someone else's address, a public address, a host name, https, a path, credentials.
        assertNull(LinkTarget.discoveredBaseUrl("http://192.168.1.99:3000", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("http://203.0.113.5:3000", "203.0.113.5"));
        assertNull(LinkTarget.discoveredBaseUrl("http://evil.example:3000", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("https://192.168.1.23:3000", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("http://192.168.1.23:3000/c/x", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("http://192.168.1.23:3000@evil.example/", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("http://192.168.1.23:3000?x=1", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl("", "192.168.1.23"));
        assertNull(LinkTarget.discoveredBaseUrl(null, null));
    }

    @Test
    public void tokenOnlyTravelsOverHttpsOrTheLocalNetwork() {
        assertTrue(LinkTarget.mayCarryToken("https://jd.example.com/c/luis"));
        assertTrue(LinkTarget.mayCarryToken("http://192.168.1.23:3000"));
        assertTrue(LinkTarget.mayCarryToken("http://10.1.2.3"));
        assertTrue(LinkTarget.mayCarryToken("http://127.0.0.1:3000"));
        assertFalse(LinkTarget.mayCarryToken("http://203.0.113.5:3000"));
        assertFalse(LinkTarget.mayCarryToken("ftp://192.168.1.23"));
        assertFalse(LinkTarget.mayCarryToken(""));
    }

    private static void assertRefused(String site, String channel) {
        try {
            String result = LinkTarget.cloudBaseUrl(site, channel);
            fail("Expected a refusal, got " + result);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
