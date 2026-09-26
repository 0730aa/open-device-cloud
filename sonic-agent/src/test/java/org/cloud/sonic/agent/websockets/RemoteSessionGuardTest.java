package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.common.maps.AndroidWebViewMap;
import org.cloud.sonic.agent.common.maps.DeviceClaimMap;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.RemoteTicketVerifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.auth0.jwt.JWT.create;
import static com.auth0.jwt.algorithms.Algorithm.HMAC256;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RemoteSessionGuardTest {
    private static final String KEY = "guard-test-agent-key";

    private int agentIdBefore;

    @Before
    public void setUp() {
        agentIdBefore = BytesTool.agentId;
        BytesTool.agentId = 7;
        new RemoteTicketVerifier().setKey(KEY);
    }

    @After
    public void tearDown() {
        BytesTool.agentId = agentIdBefore;
        new RemoteTicketVerifier().setKey(null);
        AndroidWebViewMap.getMap().clear();
    }

    @Test
    public void invalidTicketIsClosedAsPolicyViolation() throws Exception {
        Session session = newSession();

        assertNull(RemoteSessionGuard.admit(session, "forged", "serial-g1"));

        assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, closeCode(session));
        assertNull(DeviceClaimMap.holder("serial-g1"));
        assertFalse(RemoteSessionGuard.release(session));
    }

    @Test
    public void deviceServesOneUserAtATime() throws Exception {
        Session aliceScreen = newSession();
        Session aliceControl = newSession();
        Session bobScreen = newSession();

        assertEquals("alice", RemoteSessionGuard.admit(aliceScreen, ticket("alice", "serial-g2"), "serial-g2"));
        assertEquals("alice", RemoteSessionGuard.admit(aliceControl, ticket("alice", "serial-g2"), "serial-g2"));
        assertNull(RemoteSessionGuard.admit(bobScreen, ticket("bob", "serial-g2"), "serial-g2"));
        assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, closeCode(bobScreen));

        assertTrue(RemoteSessionGuard.release(aliceScreen));
        assertEquals("alice", DeviceClaimMap.holder("serial-g2"));
        assertTrue(RemoteSessionGuard.release(aliceControl));
        assertNull(DeviceClaimMap.holder("serial-g2"));

        assertEquals("bob", RemoteSessionGuard.admit(newSession(), ticket("bob", "serial-g2"), "serial-g2"));
    }

    @Test
    public void rejectingAnAdmittedSessionReleasesItsClaim() throws Exception {
        Session session = newSession();
        RemoteSessionGuard.admit(session, ticket("alice", "serial-g3"), "serial-g3");

        RemoteSessionGuard.reject(session, "device busy");

        assertNull(DeviceClaimMap.holder("serial-g3"));
        assertEquals(CloseReason.CloseCodes.TRY_AGAIN_LATER, closeCode(session));
        assertFalse("onClose must not clean up again", RemoteSessionGuard.release(session));
    }

    @Test
    public void admittedSessionIsNotClosed() throws Exception {
        Session session = newSession();

        RemoteSessionGuard.admit(session, ticket("alice", "serial-g4"), "serial-g4");

        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseReason.class));
        RemoteSessionGuard.release(session);
    }

    @Test
    public void webViewProxyOnlyReachesTheDevicesOwnForwards() {
        IDevice device = mock(IDevice.class);
        when(device.getSerialNumber()).thenReturn("serial-g5");
        JSONObject forward = new JSONObject();
        forward.put("port", 9222);
        forward.put("name", "webview_devtools_remote");
        AndroidWebViewMap.getMap().put(device, List.of(forward));

        assertTrue(WebViewWSServer.isWebViewPortOf("serial-g5", 9222));
        assertFalse(WebViewWSServer.isWebViewPortOf("serial-g5", 22));
        assertFalse(WebViewWSServer.isWebViewPortOf("serial-other", 9222));
    }

    private static String ticket(String user, String udId) {
        return create().withSubject(user).withClaim("aid", 7).withClaim("udId", udId)
                .withExpiresAt(new java.util.Date(System.currentTimeMillis() + 60_000))
                .sign(HMAC256(KEY));
    }

    private static CloseReason.CloseCode closeCode(Session session) throws Exception {
        ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
        verify(session).close(reason.capture());
        return reason.getValue().getCloseCode();
    }

    private static Session newSession() {
        Session session = mock(Session.class);
        Map<String, Object> properties = new HashMap<>();
        when(session.getUserProperties()).thenReturn(properties);
        return session;
    }
}
