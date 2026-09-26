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
package org.cloud.sonic.controller.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.cloud.sonic.common.config.WebAspect;
import org.cloud.sonic.common.http.RespEnum;
import org.cloud.sonic.common.http.RespModel;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.cloud.sonic.controller.models.base.CommentPage;
import org.cloud.sonic.controller.models.domain.DeviceSessions;
import org.cloud.sonic.controller.services.DeviceSessionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "设备使用记录")
@RestController
@RequestMapping("/deviceSessions")
public class DeviceSessionsController {

    @Autowired
    private DeviceSessionsService deviceSessionsService;
    @Autowired
    private JWTTokenTool jwtTokenTool;

    @WebAspect
    @Operation(summary = "查询设备使用记录", description = "本人的使用记录与本人设备上的使用记录，超管可查看全部")
    @Parameters(value = {
            @Parameter(name = "page", description = "页码"),
            @Parameter(name = "pageSize", description = "页数据大小")
    })
    @GetMapping("/list")
    public RespModel<CommentPage<DeviceSessions>> list(@RequestParam(name = "page") int page,
                                                     @RequestParam(name = "pageSize") int pageSize,
                                                     HttpServletRequest request) {
        String userName = jwtTokenTool.getUserName(request.getHeader("SonicToken"));
        if (userName == null) {
            return new RespModel<>(RespEnum.UNAUTHORIZED);
        }
        return new RespModel<>(RespEnum.SEARCH_OK,
                CommentPage.convertFrom(deviceSessionsService.findVisible(userName, new Page<>(page, pageSize))));
    }
}
