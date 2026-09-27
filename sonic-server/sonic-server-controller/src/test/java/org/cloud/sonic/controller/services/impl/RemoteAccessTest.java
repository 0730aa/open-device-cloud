package org.cloud.sonic.controller.services.impl;

import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.controller.mapper.AgentsMapper;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.Devices;
import org.cloud.sonic.controller.models.domain.Users;
import org.cloud.sonic.controller.models.http.OccupyParams;
import org.cloud.sonic.controller.models.interfaces.AgentStatus;
import org.cloud.sonic.controller.models.interfaces.DeviceStatus;
import org.cloud.sonic.controller.services.AgentsService;
import org.cloud.sonic.controller.services.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class RemoteAccessTest {

    @Test
    void occupyWithRemotePortsIsRefusedWhenTheAgentDoesNotAllowThem() {
        Agents agent = new Agents().setId(3).setSecretKey("agent-3-key").setStatus(AgentStatus.ONLINE).setRemoteAccess(0);
        DevicesServiceImpl devicesService = devicesServiceWith(agent);
        OccupyParams params = new OccupyParams();
        params.setUdId("serial-1");
        params.setSasRemotePort(5555);

        assertEquals(RespEnum.REMOTE_ACCESS_DISABLED.getCode(), devicesService.occupy(params, "token").getCode());
    }

    @Test
    void agentReportsWhetherItAllowsRemoteAccess() {
        AgentsServiceImpl agentsService = spy(new AgentsServiceImpl());
        Agents stored = new Agents().setId(3).setRemoteAccess(1);
        AgentsMapper agentsMapper = mock(AgentsMapper.class);
        when(agentsMapper.selectCount(any())).thenReturn(1L);
        ReflectionTestUtils.setField(agentsService, "baseMapper", agentsMapper);
        doReturn(stored).when(agentsService).findById(3);
        doReturn(true).when(agentsService).save(any(Agents.class));

        agentsService.saveAgents(agentInfo(null));
        assertEquals(0, stored.getRemoteAccess(), "agents that do not report the flag get no remote access");

        agentsService.saveAgents(agentInfo(1));
        assertEquals(1, stored.getRemoteAccess());
    }

    private static DevicesServiceImpl devicesServiceWith(Agents agent) {
        DevicesServiceImpl devicesService = spy(new DevicesServiceImpl());
        AgentsService agentsService = mock(AgentsService.class);
        UsersService usersService = mock(UsersService.class);
        when(agentsService.findById(agent.getId())).thenReturn(agent);
        when(usersService.getUserInfo("token")).thenReturn(new Users().setUserName("alice"));
        ReflectionTestUtils.setField(devicesService, "agentsService", agentsService);
        ReflectionTestUtils.setField(devicesService, "usersService", usersService);
        doReturn(new Devices().setId(1).setAgentId(agent.getId()).setUdId("serial-1").setStatus(DeviceStatus.ONLINE))
                .when(devicesService).findByUdId("serial-1");
        return devicesService;
    }

    private static JSONObject agentInfo(Integer remoteAccess) {
        JSONObject msg = new JSONObject();
        msg.put("agentId", 3);
        msg.put("host", "10.0.0.3");
        msg.put("port", 7777);
        msg.put("version", "v2.7.0");
        msg.put("systemType", "Linux");
        msg.put("remoteAccess", remoteAccess);
        return msg;
    }
}
