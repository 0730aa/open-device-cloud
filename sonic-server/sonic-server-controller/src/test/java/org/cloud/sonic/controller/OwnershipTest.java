package org.cloud.sonic.controller;

import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.common.http.RespModel;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.cloud.sonic.controller.controller.AgentsController;
import org.cloud.sonic.controller.controller.DevicesController;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.Devices;
import org.cloud.sonic.controller.models.dto.AgentsDTO;
import org.cloud.sonic.controller.models.http.DeviceDetailChange;
import org.cloud.sonic.controller.models.interfaces.DeviceStatus;
import org.cloud.sonic.controller.services.AgentsService;
import org.cloud.sonic.controller.services.DevicesService;
import org.cloud.sonic.controller.services.impl.AgentsServiceImpl;
import org.cloud.sonic.controller.services.impl.DevicesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnershipTest {

    private final JWTTokenTool jwtTokenTool = new JWTTokenTool();
    private final AgentsServiceImpl agentsService = spy(new AgentsServiceImpl());
    private final Agents aliceAgent = new Agents().setId(1).setOwnerName("alice").setSecretKey("key-1");
    private final Agents platformAgent = new Agents().setId(2).setOwnerName("").setSecretKey("key-2");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenTool, "TOKEN_SECRET", "unit-test-secret-that-is-long-enough-0123456789");
        ReflectionTestUtils.setField(jwtTokenTool, "EXPIRE_DAY", 1);
        ReflectionTestUtils.setField(agentsService, "superAdmin", "sonic");
        doReturn(aliceAgent).when(agentsService).findById(1);
        doReturn(platformAgent).when(agentsService).findById(2);
    }

    @Test
    void ownersAndTheSuperAdminManageAgents() {
        assertTrue(agentsService.canManage(aliceAgent, "alice"));
        assertFalse(agentsService.canManage(aliceAgent, "bob"));
        assertTrue(agentsService.canManage(aliceAgent, "sonic"));
        assertFalse(agentsService.canManage(platformAgent, "alice"), "platform agents belong to the super admin only");
        assertTrue(agentsService.canManage(platformAgent, "sonic"));
        assertFalse(agentsService.canManage(aliceAgent, null));
        assertFalse(agentsService.canManage(null, "sonic"));
    }

    @Test
    void agentKeysAreOnlyListedToTheirOwners() {
        AgentsController controller = agentsController();
        doReturn(List.of(aliceAgent, platformAgent)).when(agentsService).findAgents();

        List<AgentsDTO> asBob = controller.findAgents(requestAs("bob")).getData();
        List<AgentsDTO> asAlice = controller.findAgents(requestAs("alice")).getData();

        assertNull(asBob.get(0).getSecretKey());
        assertNull(asBob.get(1).getSecretKey());
        assertEquals("key-1", asAlice.get(0).getSecretKey());
        assertNull(asAlice.get(1).getSecretKey());
    }

    @Test
    void onlyTheOwnerChangesAnAgentAndNewAgentsBelongToTheirCreator() {
        AgentsController controller = agentsController();
        doReturn(null).when(agentsService).findById(0);
        org.mockito.Mockito.doNothing().when(agentsService)
                .update(anyInt(), any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());

        RespModel<String> bobEdits = controller.update(agentUpdate(1), requestAs("bob"));
        RespModel<String> bobCreates = controller.update(agentUpdate(0), requestAs("bob"));

        assertEquals(RespEnum.PERMISSION_DENIED.getCode(), bobEdits.getCode());
        verify(agentsService, never()).update(eq(1), any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
        assertEquals(RespEnum.HANDLE_OK.getCode(), bobCreates.getCode());
        verify(agentsService).update(eq(0), any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), eq("bob"));
    }

    @Test
    void deviceSettingsBelongToTheAgentOwner() {
        DevicesService devicesService = mock(DevicesService.class);
        DevicesController controller = devicesController(devicesService);
        Devices device = new Devices().setId(10).setAgentId(1).setUdId("serial-1").setStatus(DeviceStatus.DEBUGGING).setUser("carol");
        when(devicesService.findById(10)).thenReturn(device);
        when(devicesService.findByUdId("serial-1")).thenReturn(device);
        when(devicesService.saveDetail(any())).thenReturn(true);
        DeviceDetailChange change = new DeviceDetailChange();
        change.setId(10);

        assertEquals(RespEnum.PERMISSION_DENIED.getCode(), controller.saveDetail(change, requestAs("bob")).getCode());
        assertEquals(RespEnum.PERMISSION_DENIED.getCode(), controller.delete(10, requestAs("bob")).getCode());
        assertEquals(RespEnum.PERMISSION_DENIED.getCode(), controller.updatePosition(10, 3, requestAs("bob")).getCode());
        assertEquals(RespEnum.PERMISSION_DENIED.getCode(), controller.stopDebug("serial-1", requestAs("bob")).getCode());
        verify(devicesService, never()).saveDetail(any());
        verify(devicesService, never()).delete(anyInt());

        assertEquals(RespEnum.UPDATE_OK.getCode(), controller.saveDetail(change, requestAs("alice")).getCode());
    }

    @Test
    void anotherOwnersAgentCannotTakeOverADevice() {
        Agents bobAgent = new Agents().setId(3).setOwnerName("bob");
        Agents aliceSecondAgent = new Agents().setId(4).setOwnerName("alice");
        doReturn(bobAgent).when(agentsService).findById(3);
        doReturn(aliceSecondAgent).when(agentsService).findById(4);
        doReturn(null).when(agentsService).findById(5);
        DevicesServiceImpl devicesService = spy(new DevicesServiceImpl());
        ReflectionTestUtils.setField(devicesService, "agentsService", agentsService);
        Devices device = new Devices().setId(10).setAgentId(1).setUdId("serial-1").setStatus(DeviceStatus.ONLINE);
        doReturn(device).when(devicesService).findByUdId("serial-1");
        doReturn(true).when(devicesService).save(any(Devices.class));

        devicesService.deviceStatus(report(3));
        assertEquals(1, device.getAgentId(), "bob's agent must not take alice's device");

        devicesService.deviceStatus(report(4));
        assertEquals(4, device.getAgentId(), "alice may move her phone to another of her agents");

        device.setAgentId(5);
        devicesService.deviceStatus(report(3));
        assertEquals(3, device.getAgentId(), "a device whose agent was deleted can be registered again");
    }

    private AgentsController agentsController() {
        AgentsController controller = new AgentsController();
        ReflectionTestUtils.setField(controller, "agentsService", agentsService);
        ReflectionTestUtils.setField(controller, "jwtTokenTool", jwtTokenTool);
        return controller;
    }

    private DevicesController devicesController(DevicesService devicesService) {
        DevicesController controller = new DevicesController();
        ReflectionTestUtils.setField(controller, "devicesService", devicesService);
        ReflectionTestUtils.setField(controller, "agentsService", (AgentsService) agentsService);
        ReflectionTestUtils.setField(controller, "jwtTokenTool", jwtTokenTool);
        return controller;
    }

    private MockHttpServletRequest requestAs(String userName) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("SonicToken", jwtTokenTool.getToken(userName));
        return request;
    }

    private static AgentsDTO agentUpdate(int id) {
        return new AgentsDTO().setId(id).setName("agent").setHighTemp(45).setHighTempTime(15).setRobotType(1);
    }

    private static JSONObject report(int agentId) {
        JSONObject msg = new JSONObject();
        msg.put("msg", "deviceDetail");
        msg.put("agentId", agentId);
        msg.put("udId", "serial-1");
        msg.put("status", DeviceStatus.ONLINE);
        return msg;
    }
}
