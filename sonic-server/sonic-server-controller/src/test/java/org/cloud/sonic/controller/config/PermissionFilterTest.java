package org.cloud.sonic.controller.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.FilterChain;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PermissionFilterTest {

    private final PermissionFilter filter = new PermissionFilter();
    private final JWTTokenTool jwtTokenTool = new JWTTokenTool();
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenTool, "TOKEN_SECRET", "unit-test-secret-that-is-long-enough-0123456789");
        ReflectionTestUtils.setField(jwtTokenTool, "EXPIRE_DAY", 1);
        ReflectionTestUtils.setField(filter, "jwtTokenTool", jwtTokenTool);
        ReflectionTestUtils.setField(filter, "permissionEnable", true);
        ReflectionTestUtils.setField(filter, "superAdmin", "sonic");
        ReflectionTestUtils.setField(filter, "messageSource", mock(MessageSource.class));
    }

    @Test
    void forgedSuperAdminTokenIsRejected() throws Exception {
        String forged = JWT.create().withAudience("sonic", "x").sign(Algorithm.HMAC256("guessed-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(forged), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertTrue(response.getContentAsString().contains("1001"));
    }

    @Test
    void validSuperAdminTokenPasses() throws Exception {
        filter.doFilter(request(jwtTokenTool.getToken("sonic")), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agents/list");
        request.setServletPath("/agents/list");
        request.addHeader("SonicToken", token);
        return request;
    }
}
