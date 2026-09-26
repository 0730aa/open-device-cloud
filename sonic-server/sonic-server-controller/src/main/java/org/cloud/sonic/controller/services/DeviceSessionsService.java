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
package org.cloud.sonic.controller.services;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.cloud.sonic.controller.models.domain.Agents;
import org.cloud.sonic.controller.models.domain.DeviceSessions;
import org.cloud.sonic.controller.models.domain.Devices;

public interface DeviceSessionsService extends IService<DeviceSessions> {

    /**
     * A user holding a verified ticket started using the device. Continues the open session if it
     * is already theirs; otherwise ends it and opens a new one.
     */
    void start(Devices devices, Agents agents, String userName, String ticketId);

    /**
     * End whatever session is open on the device.
     */
    void end(int deviceId, String reason);

    DeviceSessions findOpen(int deviceId);

    /**
     * Sessions the user may see: their own and those on devices they own; all for the super admin.
     */
    Page<DeviceSessions> findVisible(String userName, Page<DeviceSessions> page);
}
