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
package org.cloud.sonic.controller.models.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gitee.sunchenbin.mybatis.actable.annotation.*;
import com.gitee.sunchenbin.mybatis.actable.constants.MySqlCharsetConstant;
import com.gitee.sunchenbin.mybatis.actable.constants.MySqlEngineConstant;
import com.gitee.sunchenbin.mybatis.actable.constants.MySqlTypeConstant;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * One user's continuous use of one device, the unit usage is metered and billed by.
 */
@Schema(name = "DeviceSessions对象", description = "设备使用记录")
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("device_sessions")
@TableComment("设备使用记录，用于计量与计费")
@TableCharset(MySqlCharsetConstant.DEFAULT)
@TableEngine(MySqlEngineConstant.InnoDB)
public class DeviceSessions implements Serializable {
    public static final String RELEASED = "released";
    public static final String DEVICE_LOST = "device_lost";
    public static final String AGENT_OFFLINE = "agent_offline";
    public static final String SUPERSEDED = "superseded";

    @TableId(value = "id", type = IdType.AUTO)
    @IsAutoIncrement
    private Integer id;

    @TableField
    @Column(value = "device_id", isNull = false, comment = "设备id")
    @Index(value = "IDX_DEVICE_ID", columns = {"device_id"})
    private Integer deviceId;

    @TableField
    @Column(value = "agent_id", isNull = false, comment = "会话开始时设备所在agent的id")
    private Integer agentId;

    @TableField
    @Column(value = "owner_name", isNull = false, comment = "会话开始时agent的机主，为空表示平台自营", defaultValue = "")
    @Index(value = "IDX_OWNER_NAME", columns = {"owner_name"})
    private String ownerName;

    @TableField
    @Column(value = "user_name", isNull = false, comment = "使用者")
    @Index(value = "IDX_USER_NAME", columns = {"user_name"})
    private String userName;

    @TableField
    @Column(value = "ticket_id", isNull = false, comment = "开启会话的远程票据id，用于对账", defaultValue = "")
    private String ticketId;

    @TableField
    @Column(value = "start_time", type = MySqlTypeConstant.DATETIME, isNull = false, comment = "开始时间")
    private Date startTime;

    @TableField
    @Column(value = "end_time", type = MySqlTypeConstant.DATETIME, comment = "结束时间，为空表示仍在使用")
    private Date endTime;

    @TableField
    @Column(value = "end_reason", isNull = false, comment = "结束原因：released/device_lost/agent_offline/superseded", defaultValue = "")
    private String endReason;
}
