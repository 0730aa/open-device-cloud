package org.cloud.sonic.controller.transport;

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.ConfList;
import org.cloud.sonic.controller.models.interfaces.ConfType;
import org.cloud.sonic.controller.services.AgentsService;
import org.cloud.sonic.controller.services.ConfListService;
import org.cloud.sonic.controller.services.DevicesService;
import org.cloud.sonic.controller.tools.BytesTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransportServerTest {

    @Mock
    private AgentsService agentsService;
    @Mock
    private DevicesService devicesService;
    @Mock
    private ConfListService confListService;

    @InjectMocks
    private TransportServer transportServer;

    @AfterEach
    void clearSessions() {
        BytesTool.agentSessionMap.clear();
    }

    @Test
    void messagesFromUnauthenticatedSessionsAreIgnored() {
        Session session = newSession();

        transportServer.onMessage("{\"msg\":\"deviceDetail\",\"agentId\":1,\"udId\":\"serial\"}", session);

        verifyNoInteractions(devicesService, agentsService);
    }

    @Test
    void failedAuthDoesNotAuthenticateTheSession() throws Exception {
        Session session = newSession();
        when(agentsService.auth("bad-key")).thenReturn(null);

        transportServer.onOpen(session, "bad-key");
        transportServer.onMessage("{\"msg\":\"deviceDetail\",\"agentId\":1,\"udId\":\"serial\"}", session);

        assertFalse(session.getUserProperties().containsKey(TransportServer.AGENT_ID));
        verifyNoInteractions(devicesService);
    }

    @Test
    void agentIdInMessageIsReplacedByTheAuthenticatedOne() throws Exception {
        Session session = authenticatedSession(1);

        transportServer.onMessage("{\"msg\":\"deviceDetail\",\"agentId\":2,\"udId\":\"serial\"}", session);

        ArgumentCaptor<JSONObject> reported = ArgumentCaptor.forClass(JSONObject.class);
        verify(devicesService).deviceStatus(reported.capture());
        assertEquals(1, reported.getValue().getInteger("agentId"));
    }

    @Test
    void agentInfoCannotRegisterAsAnotherAgent() throws Exception {
        Session victim = authenticatedSession(2);
        transportServer.onMessage("{\"msg\":\"agentInfo\",\"agentId\":2,\"host\":\"10.0.0.2\",\"port\":7777}", victim);
        Session attacker = authenticatedSession(1);

        transportServer.onMessage("{\"msg\":\"agentInfo\",\"agentId\":2,\"host\":\"6.6.6.6\",\"port\":7777}", attacker);

        assertSame(victim, BytesTool.agentSessionMap.get(2));
        assertSame(attacker, BytesTool.agentSessionMap.get(1));
        ArgumentCaptor<JSONObject> saved = ArgumentCaptor.forClass(JSONObject.class);
        verify(agentsService, times(2)).saveAgents(saved.capture());
        assertEquals(1, saved.getAllValues().get(1).getInteger("agentId"));
        verify(victim, never()).close();
    }

    @Test
    void reconnectReplacesSessionWithoutTakingAgentOffline() throws Exception {
        Session first = authenticatedSession(1);
        transportServer.onMessage("{\"msg\":\"agentInfo\",\"host\":\"10.0.0.1\",\"port\":7777}", first);
        Session second = authenticatedSession(1);

        transportServer.onMessage("{\"msg\":\"agentInfo\",\"host\":\"10.0.0.1\",\"port\":7777}", second);
        verify(first).close();
        transportServer.onClose(first);

        verify(agentsService, never()).offLine(1);
        assertSame(second, BytesTool.agentSessionMap.get(1));

        transportServer.onClose(second);

        verify(agentsService).offLine(1);
        assertTrue(BytesTool.agentSessionMap.isEmpty());
    }

    @Test
    void pingIsAnsweredOnTheSameSession() throws Exception {
        Session session = authenticatedSession(1);
        RemoteEndpoint.Basic remote = session.getBasicRemote();

        transportServer.onMessage("{\"msg\":\"ping\",\"agentId\":2}", session);

        verify(remote).sendText("{\"msg\":\"pong\"}");
    }

    private Session authenticatedSession(int agentId) throws Exception {
        Session session = newSession();
        String key = "key-" + agentId;
        when(agentsService.auth(key)).thenReturn(new Agents().setId(agentId).setHighTemp(45).setHighTempTime(15));
        lenient().when(confListService.searchByKey(ConfType.REMOTE_DEBUG_TIMEOUT))
                .thenReturn(new ConfList().setContent("480"));
        transportServer.onOpen(session, key);
        return session;
    }

    private static Session newSession() {
        Session session = mock(Session.class);
        Map<String, Object> properties = new HashMap<>();
        lenient().when(session.getUserProperties()).thenReturn(properties);
        lenient().when(session.getId()).thenReturn(Integer.toHexString(System.identityHashCode(session)));
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        return session;
    }
}
