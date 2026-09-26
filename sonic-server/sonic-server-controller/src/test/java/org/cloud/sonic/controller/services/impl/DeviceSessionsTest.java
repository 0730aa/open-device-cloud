package org.cloud.sonic.controller.services.impl;

import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.DeviceSessions;
import org.cloud.sonic.controller.models.domain.Devices;
import org.cloud.sonic.controller.models.interfaces.DeviceStatus;
import org.cloud.sonic.controller.services.AgentsService;
import org.cloud.sonic.controller.services.DeviceSessionsService;
import org.cloud.sonic.controller.services.DevicesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceSessionsTest {

    private final Agents agent = new Agents().setId(3).setOwnerName("olivia");
    private final Devices device = new Devices().setId(10).setAgentId(3).setUdId("serial-1").setStatus(DeviceStatus.DEBUGGING);

    @Test
    void sessionIsOpenedForTheTicketHolderAndCreditsTheOwner() {
        DeviceSessionsServiceImpl sessions = sessionsWithOpen(null);

        sessions.start(device, agent, "alice", "ticket-1");

        ArgumentCaptor<DeviceSessions> saved = ArgumentCaptor.forClass(DeviceSessions.class);
        verify(sessions).save(saved.capture());
        assertEquals(10, saved.getValue().getDeviceId());
        assertEquals("alice", saved.getValue().getUserName());
        assertEquals("olivia", saved.getValue().getOwnerName());
        assertEquals("ticket-1", saved.getValue().getTicketId());
        assertNotNull(saved.getValue().getStartTime());
        assertNull(saved.getValue().getEndTime());
    }

    @Test
    void sameUserReconnectingKeepsTheirSession() {
        DeviceSessionsServiceImpl sessions = sessionsWithOpen(new DeviceSessions().setId(1).setDeviceId(10).setUserName("alice"));

        sessions.start(device, agent, "alice", "ticket-2");

        verify(sessions, never()).save(any(DeviceSessions.class));
        verify(sessions, never()).end(anyInt(), anyString());
    }

    @Test
    void anotherUsersSessionIsClosedBeforeANewOneOpens() {
        DeviceSessionsServiceImpl sessions = sessionsWithOpen(new DeviceSessions().setId(1).setDeviceId(10).setUserName("bob"));

        sessions.start(device, agent, "alice", "ticket-3");

        verify(sessions).end(10, DeviceSessions.SUPERSEDED);
        verify(sessions).save(any(DeviceSessions.class));
    }

    @Test
    void sessionEndsWhenTheDeviceLeavesDebugging() {
        DeviceSessionsService sessions = mock(DeviceSessionsService.class);
        DevicesServiceImpl devicesService = devicesServiceWith(sessions);

        devicesService.deviceStatus(report(DeviceStatus.DEBUGGING));
        verify(sessions, never()).end(anyInt(), anyString());

        devicesService.deviceStatus(report(DeviceStatus.ONLINE));
        verify(sessions).end(10, DeviceSessions.RELEASED);

        devicesService.deviceStatus(report(DeviceStatus.DISCONNECTED));
        verify(sessions).end(10, DeviceSessions.DEVICE_LOST);

        devicesService.deviceStatus(report(DeviceStatus.TESTING));
        verify(sessions).end(10, DeviceSessions.SUPERSEDED);
    }

    @Test
    void sessionsEndWhenTheirAgentGoesOffline() {
        DeviceSessionsService sessions = mock(DeviceSessionsService.class);
        DevicesService devicesService = mock(DevicesService.class);
        AgentsServiceImpl agentsService = new AgentsServiceImpl();
        ReflectionTestUtils.setField(agentsService, "devicesService", devicesService);
        ReflectionTestUtils.setField(agentsService, "deviceSessionsService", sessions);
        Devices idle = new Devices().setId(11).setAgentId(3).setStatus(DeviceStatus.OFFLINE);
        when(devicesService.listByAgentId(3)).thenReturn(List.of(device, idle));

        agentsService.resetDevice(3);

        verify(sessions).end(10, DeviceSessions.AGENT_OFFLINE);
        verify(sessions, never()).end(11, DeviceSessions.AGENT_OFFLINE);
    }

    private static DeviceSessionsServiceImpl sessionsWithOpen(DeviceSessions open) {
        DeviceSessionsServiceImpl sessions = spy(new DeviceSessionsServiceImpl());
        doReturn(open).when(sessions).findOpen(10);
        doNothing().when(sessions).end(anyInt(), anyString());
        doReturn(true).when(sessions).save(any(DeviceSessions.class));
        return sessions;
    }

    private DevicesServiceImpl devicesServiceWith(DeviceSessionsService sessions) {
        DevicesServiceImpl devicesService = spy(new DevicesServiceImpl());
        AgentsService agentsService = mock(AgentsService.class);
        when(agentsService.findById(3)).thenReturn(agent);
        ReflectionTestUtils.setField(devicesService, "agentsService", agentsService);
        ReflectionTestUtils.setField(devicesService, "deviceSessionsService", sessions);
        doReturn(device).when(devicesService).findByUdId("serial-1");
        doReturn(true).when(devicesService).save(any(Devices.class));
        return devicesService;
    }

    private static JSONObject report(String status) {
        JSONObject msg = new JSONObject();
        msg.put("msg", "deviceDetail");
        msg.put("agentId", 3);
        msg.put("udId", "serial-1");
        msg.put("status", status);
        return msg;
    }
}
