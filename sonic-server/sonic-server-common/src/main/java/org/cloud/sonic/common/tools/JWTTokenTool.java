/*
 *   sonic-server  Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
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
package org.cloud.sonic.common.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * @author ZhouYiXun
 * @des Token加解密工具类
 * @date 2021/8/15 18:26
 */
@Configuration
public class JWTTokenTool {
    /**
     * Claim that marks a token as server-to-server only. User tokens never carry it.
     */
    private static final String SCOPE_CLAIM = "scope";
    private static final String INTERNAL_SCOPE = "sonic-internal";
    private static final long INTERNAL_TOKEN_TTL_MILLIS = 60_000L;
    static final int MIN_SECRET_LENGTH = 32;

    @Value("${sonic.token.secret}")
    private String TOKEN_SECRET;
    @Value("${sonic.token.expireDay}")
    private int EXPIRE_DAY;

    /**
     * Whoever knows the secret can sign a token for any user, super admin included, so refuse to
     * start with a missing or guessable one (it used to default to "sonic").
     */
    @PostConstruct
    void checkSecret() {
        if (TOKEN_SECRET == null || TOKEN_SECRET.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("sonic.token.secret (env SECRET_KEY) must be a random string of at least "
                    + MIN_SECRET_LENGTH + " characters, e.g. the output of `openssl rand -base64 48`, "
                    + "and the same for every server component.");
        }
    }

    /**
     * @param username
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 通过用户名生成token
     * @date 2021/8/15 23:05
     */
    public String getToken(String username) {
        Calendar now = Calendar.getInstance();
        now.add(Calendar.DATE, EXPIRE_DAY);
        Date nowTime = now.getTime();
        return JWT.create().withAudience(username, UUID.randomUUID().toString())
                .withExpiresAt(nowTime)
                .sign(Algorithm.HMAC256(TOKEN_SECRET));
    }

    public String getToken(String username, int day) {
        Calendar now = Calendar.getInstance();
        now.add(Calendar.DATE, day);
        Date nowTime = now.getTime();
        return JWT.create().withAudience(username, UUID.randomUUID().toString())
                .withExpiresAt(nowTime)
                .sign(Algorithm.HMAC256(TOKEN_SECRET));
    }

    /**
     * @param token
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 由token获取生成时的用户信息
     * @date 2021/8/15 23:05
     */
    public String getUserName(String token) {
        try {
            List<String> audience = JWT.decode(token).getAudience();
            return audience == null || audience.isEmpty() ? null : audience.get(0);
        } catch (JWTDecodeException e) {
            return null;
        }
    }

    /**
     * @param token
     * @return boolean
     * @author ZhouYiXun
     * @des 校验token的签名是否合法
     * @date 2021/8/15 23:05
     */
    public boolean verify(String token) {
        JWTVerifier jwtVerifier = JWT.require(Algorithm.HMAC256(TOKEN_SECRET)).build();
        try {
            DecodedJWT jwt = jwtVerifier.verify(token);
            // Internal tokens have no user, so they must never pass as a user's token.
            return jwt.getClaim(SCOPE_CLAIM).isMissing();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Short-lived token that server components attach to server-to-server calls
     * (e.g. relaying a message to the controller instance an agent is connected to).
     */
    public String getInternalToken() {
        return JWT.create().withClaim(SCOPE_CLAIM, INTERNAL_SCOPE)
                .withExpiresAt(new Date(System.currentTimeMillis() + INTERNAL_TOKEN_TTL_MILLIS))
                .sign(Algorithm.HMAC256(TOKEN_SECRET));
    }

    public boolean verifyInternal(String token) {
        if (token == null) {
            return false;
        }
        try {
            JWT.require(Algorithm.HMAC256(TOKEN_SECRET)).withClaim(SCOPE_CLAIM, INTERNAL_SCOPE).build().verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}