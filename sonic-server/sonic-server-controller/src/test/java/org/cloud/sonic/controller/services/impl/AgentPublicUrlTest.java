package org.cloud.sonic.controller.services.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPublicUrlTest {

    @Test
    void acceptsWebOriginsAndDropsTrailingSlashes() {
        assertEquals("https://phone-1.example.com", AgentsServiceImpl.normalizePublicUrl("https://phone-1.example.com/"));
        assertEquals("wss://phone-1.example.com:8443/agent", AgentsServiceImpl.normalizePublicUrl("wss://phone-1.example.com:8443/agent"));
        assertEquals("http://10.0.0.8:7777", AgentsServiceImpl.normalizePublicUrl("http://10.0.0.8:7777"));
    }

    @Test
    void rejectsAnythingElse() {
        assertEquals("", AgentsServiceImpl.normalizePublicUrl(null));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl(""));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("javascript:alert(1)"));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("ftp://phone-1.example.com"));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("https://user@phone-1.example.com"));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("https://phone-1.example.com/?next=x"));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("https://phone-1.example.com/\"><script>"));
        assertEquals("", AgentsServiceImpl.normalizePublicUrl("phone-1.example.com"));
    }
}
