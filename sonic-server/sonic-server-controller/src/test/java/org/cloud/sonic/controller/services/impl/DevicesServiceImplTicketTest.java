package org.cloud.sonic.controller.services.impl;

import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.common.http.RespModel;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.Devices;
import org.cloud.sonic.controller.models.interfaces.AgentStatus;
import org.cloud.sonic.controller.models.interfaces.DeviceStatus;
import org.cloud.sonic.controller.services.AgentsService;
import org.cloud.sonic.controller.services.DeviceSessionsService;
import org.cloud.sonic.controller.tools.RemoteTicketTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevicesServiceImplTicketTest {

    private final Agents agent = new Agents().setId(3).setSecretKey("agent-3-key").setStatus(AgentStatus.ONLINE);
    private final AgentsService agentsService = mock(AgentsService.class);
    private final DeviceSessionsService deviceSessionsService = mock(DeviceSessionsService.class);
    private DevicesServiceImpl devicesService;

    @BeforeEach
    void setUp() {
        devicesService = spy(new DevicesServiceImpl());
        ReflectionTestUtils.setField(devicesService, "agentsService", agentsService);
        ReflectionTestUtils.setField(devicesService, "deviceSessionsService", deviceSessionsService);
        when(agentsService.findById(3)).thenReturn(agent);
    }

    @Test
    void freeDeviceGetsATicketForTheRequestingUser() {
        givenDevice(DeviceStatus.ONLINE, "");

        RespModel<JSONObject> resp = devicesService.remoteTicket(1, "alice");

        assertEquals(RespEnum.SEARCH_OK.getCode(), resp.getCode());
        assertEquals("alice", RemoteTicketTool.verify(agent, "serial-1", resp.getData().getString("ticket")));
    }

    @Test
    void userCanReconnectToTheDeviceTheyAreUsing() {
        givenDevice(DeviceStatus.DEBUGGING, "alice");

        assertEquals(RespEnum.SEARCH_OK.getCode(), devicesService.remoteTicket(1, "alice").getCode());
    }

    @Test
    void deviceUsedBySomeoneElseIsBusy() {
        givenDevice(DeviceStatus.DEBUGGING, "bob");

        assertEquals(RespEnum.DEVICE_BUSY.getCode(), devicesService.remoteTicket(1, "alice").getCode());
    }

    @Test
    void deviceUnderTestIsBusy() {
        givenDevice(DeviceStatus.TESTING, "");

        assertEquals(RespEnum.DEVICE_BUSY.getCode(), devicesService.remoteTicket(1, "alice").getCode());
    }

    @Test
    void noTicketWhenTheAgentIsOffline() {
        givenDevice(DeviceStatus.ONLINE, "");
        agent.setStatus(AgentStatus.OFFLINE);

        assertEquals(RespEnum.AGENT_NOT_ONLINE.getCode(), devicesService.remoteTicket(1, "alice").getCode());
    }

    @Test
    void noTicketForUnknownDevice() {
        doReturn(null).when(devicesService).findById(1);

        assertEquals(RespEnum.DEVICE_NOT_FOUND.getCode(), devicesService.remoteTicket(1, "alice").getCode());
    }

    @Test
    void agentReportedUserIsAcceptedOnlyWithAValidTicket() {
        Devices device = givenDevice(DeviceStatus.DEBUGGING, "");
        doReturn(device).when(devicesService).findByAgentIdAndUdId(3, "serial-1");
        doReturn(true).when(devicesService).save(any(Devices.class));

        devicesService.updateDevicesUser(debugUser("forged"));
        assertEquals("", device.getUser());
        verify(devicesService, never()).save(any(Devices.class));
        verify(deviceSessionsService, never()).start(any(), any(), any(), any());

        String ticket = RemoteTicketTool.issue(agent, "serial-1", "alice");
        devicesService.updateDevicesUser(debugUser(ticket));
        assertEquals("alice", device.getUser());
        verify(deviceSessionsService).start(device, agent, "alice", com.auth0.jwt.JWT.decode(ticket).getId());
    }

    private Devices givenDevice(String status, String user) {
        Devices device = new Devices().setId(1).setAgentId(3).setUdId("serial-1").setStatus(status).setUser(user);
        doReturn(device).when(devicesService).findById(1);
        return device;
    }

    private static JSONObject debugUser(String ticket) {
        JSONObject msg = new JSONObject();
        msg.put("msg", "debugUser");
        msg.put("agentId", 3);
        msg.put("udId", "serial-1");
        msg.put("ticket", ticket);
        return msg;
    }
}
