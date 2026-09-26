/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
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
package org.cloud.sonic.agent.tests.script;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Groovy and Python script steps run with the agent's own privileges on the machine hosting
 * the agent, so anyone who can write a test case could run anything on the agent owner's
 * computer. They stay off unless the agent owner opts in with sonic.agent.script.enable.
 */
@Component
public class ScriptPolicy {
    private static volatile boolean enabled = false;

    @Value("${sonic.agent.script.enable:false}")
    public void setEnabled(boolean enabled) {
        ScriptPolicy.enabled = enabled;
    }

    public static void checkEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Script steps are disabled on this agent. "
                    + "The agent owner can allow them with sonic.agent.script.enable=true.");
        }
    }
}
