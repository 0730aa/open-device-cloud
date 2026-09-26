package org.cloud.sonic.controller.controller;

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.common.http.RespModel;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.cloud.sonic.controller.tools.BytesTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeControllerTest {

    private final JWTTokenTool jwtTokenTool = new JWTTokenTool();
    private final ExchangeController controller = new ExchangeController();
    private final RemoteEndpoint.Basic agentRemote = mock(RemoteEndpoint.Basic.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenTool, "TOKEN_SECRET", "unit-test-secret-that-is-long-enough-0123456789");
        ReflectionTestUtils.setField(jwtTokenTool, "EXPIRE_DAY", 1);
        ReflectionTestUtils.setField(controller, "jwtTokenTool", jwtTokenTool);
        Session agentSession = mock(Session.class);
        when(agentSession.isOpen()).thenReturn(true);
        when(agentSession.getBasicRemote()).thenReturn(agentRemote);
        BytesTool.agentSessionMap.put(1, agentSession);
    }

    @AfterEach
    void clearSessions() {
        BytesTool.agentSessionMap.clear();
    }

    @Test
    void relayWithoutInternalTokenIsRejected() throws Exception {
        RespModel<String> resp = controller.send(1, shutdown(), new MockHttpServletRequest());

        assertEquals(RespEnum.UNAUTHORIZED.getCode(), resp.getCode());
        verify(agentRemote, never()).sendText(anyString());
    }

    @Test
    void relayWithAUserTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ExchangeController.INTERNAL_TOKEN_HEADER, jwtTokenTool.getToken("alice"));

        RespModel<String> resp = controller.send(1, shutdown(), request);

        assertEquals(RespEnum.UNAUTHORIZED.getCode(), resp.getCode());
        verify(agentRemote, never()).sendText(anyString());
    }

    @Test
    void relayWithInternalTokenReachesTheAgent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ExchangeController.INTERNAL_TOKEN_HEADER, jwtTokenTool.getInternalToken());

        RespModel<String> resp = controller.send(1, shutdown(), request);

        assertEquals(RespEnum.SEND_OK.getCode(), resp.getCode());
        verify(agentRemote).sendText("{\"msg\":\"shutdown\"}");
    }

    private static JSONObject shutdown() {
        JSONObject msg = new JSONObject();
        msg.put("msg", "shutdown");
        return msg;
    }
}
