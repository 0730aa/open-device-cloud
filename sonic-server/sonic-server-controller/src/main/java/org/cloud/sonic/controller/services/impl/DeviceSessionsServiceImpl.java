/*
 *   sonic-server  Sonic Cloud Real Machine Platform.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.controller.services.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cloud.sonic.controller.mapper.DeviceSessionsMapper;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.DeviceSessions;
import org.cloud.sonic.controller.models.domain.Devices;
import org.cloud.sonic.controller.services.DeviceSessionsService;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class DeviceSessionsServiceImpl extends SonicServiceImpl<DeviceSessionsMapper, DeviceSessions> implements DeviceSessionsService {

    @Value("${sonic.permission.superAdmin}")
    private String superAdmin;

    @Override
    public void start(Devices devices, Agents agents, String userName, String ticketId) {
        DeviceSessions open = findOpen(devices.getId());
        if (open != null && open.getUserName().equals(userName)) {
            return;
        }
        end(devices.getId(), DeviceSessions.SUPERSEDED);
        save(new DeviceSessions()
                .setDeviceId(devices.getId())
                .setAgentId(agents.getId())
                .setOwnerName(agents.getOwnerName() == null ? "" : agents.getOwnerName())
                .setUserName(userName)
                .setTicketId(ticketId == null ? "" : ticketId)
                .setStartTime(new Date())
                .setEndReason(""));
    }

    @Override
    public void end(int deviceId, String reason) {
        lambdaUpdate().eq(DeviceSessions::getDeviceId, deviceId)
                .isNull(DeviceSessions::getEndTime)
                .set(DeviceSessions::getEndTime, new Date())
                .set(DeviceSessions::getEndReason, reason)
                .update();
    }

    @Override
    public DeviceSessions findOpen(int deviceId) {
        return lambdaQuery().eq(DeviceSessions::getDeviceId, deviceId)
                .isNull(DeviceSessions::getEndTime)
                .orderByDesc(DeviceSessions::getId)
                .last("limit 1")
                .one();
    }

    @Override
    public Page<DeviceSessions> findVisible(String userName, Page<DeviceSessions> page) {
        if (userName.equals(superAdmin)) {
            return lambdaQuery().orderByDesc(DeviceSessions::getId).page(page);
        }
        return lambdaQuery()
                .and(q -> q.eq(DeviceSessions::getUserName, userName).or().eq(DeviceSessions::getOwnerName, userName))
                .orderByDesc(DeviceSessions::getId)
                .page(page);
    }
}
