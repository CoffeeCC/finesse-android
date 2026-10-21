package org.jellyfin.mobile.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinesseUrlsTest {
    @Test
    fun `a Finesse address becomes the Jellyfin address behind it`() {
        assertEquals("192.168.1.20:8080/jellyfin", FinesseUrls.jellyfinAddress("192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:8080/jellyfin", FinesseUrls.jellyfinAddress("http://192.168.1.20:8080/"))
        assertEquals("http://nas:8080/jellyfin", FinesseUrls.jellyfinAddress("http://nas:8080/finesse/"))
        assertEquals("http://nas:8080/jellyfin", FinesseUrls.jellyfinAddress(" http://nas:8080/Jellyfin "))
        assertEquals("https://home.example.ts.net/jellyfin", FinesseUrls.jellyfinAddress("https://home.example.ts.net"))
    }

    @Test
    fun `the stored Jellyfin address opens the Finesse app`() {
        assertEquals("http://nas:8080/finesse/", FinesseUrls.webappAddress("http://nas:8080/jellyfin"))
        assertEquals("http://nas:8080/finesse/", FinesseUrls.webappAddress("http://nas:8080/jellyfin/"))
        assertEquals("https://home.example.ts.net/finesse/", FinesseUrls.webappAddress("https://home.example.ts.net/jellyfin"))
    }

    @Test
    fun `only the port that was typed counts`() {
        assertTrue(FinesseUrls.samePort("192.168.1.20:8080", "http://192.168.1.20:8080/jellyfin"))
        assertFalse(FinesseUrls.samePort("192.168.1.20:8080", "http://192.168.1.20:8096/jellyfin"))
        assertTrue(FinesseUrls.samePort("https://home.example.ts.net", "https://home.example.ts.net/jellyfin"))
        assertFalse(FinesseUrls.samePort("https://home.example.ts.net", "https://home.example.ts.net:8096/jellyfin"))
        // Nothing but a host typed: any port Finesse answers on.
        assertTrue(FinesseUrls.samePort("nas", "http://nas:8096/jellyfin"))
    }
}
