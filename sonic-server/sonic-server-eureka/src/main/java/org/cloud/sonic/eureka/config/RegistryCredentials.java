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
package org.cloud.sonic.eureka.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

/**
 * Refuses to start with a guessable registry password, such as the "sonic" that .env ships
 * with: whoever knows it can register instances the gateway sends traffic to.
 */
@Configuration
public class RegistryCredentials {
    static final int MIN_PASSWORD_LENGTH = 16;
    /**
     * The password is put into every component's registry URL (http://user:password@host/eureka/),
     * where characters such as @ : / would break it.
     */
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9._~-]+");

    @Value("${spring.security.user.password:}")
    private String password;

    @PostConstruct
    void checkPassword() {
        check(password);
    }

    static void check(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || !URL_SAFE.matcher(password).matches()) {
            throw new IllegalStateException("Set SONIC_EUREKA_PASSWORD to at least " + MIN_PASSWORD_LENGTH
                    + " letters, digits or ._~- (e.g. from `openssl rand -hex 24`), the same for every server component.");
        }
    }
}
