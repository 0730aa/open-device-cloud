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
package org.cloud.sonic.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Forwards browsers' remote-control connections to agents that cannot accept connections
 * themselves, e.g. a phone owner's computer behind a home router. Agents keep a control
 * connection open to the relay; for every browser connection the relay asks the agent to
 * connect back, then copies messages between the two.
 */
@SpringBootApplication
public class RelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
