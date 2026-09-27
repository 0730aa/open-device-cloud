package org.cloud.sonic.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebAspectConfigTest {

    private final WebAspectConfig aspect = new WebAspectConfig();
    private final JWTTokenTool jwtTokenTool = new JWTTokenTool();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private String token;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtTokenTool, "TOKEN_SECRET", "unit-test-secret-that-is-long-enough-0123456789");
        ReflectionTestUtils.setField(jwtTokenTool, "EXPIRE_DAY", 1);
        ReflectionTestUtils.setField(aspect, "jwtTokenTool", jwtTokenTool);
        token = jwtTokenTool.getToken("alice");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users/login");
        request.addHeader("SonicToken", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        logs.start();
        ((Logger) LoggerFactory.getLogger(WebAspectConfig.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(WebAspectConfig.class)).detachAppender(logs);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sensitiveEndpointsLogNeitherArgumentsNorResponse() throws Throwable {
        JoinPoint joinPoint = joinPoint("login", "password=hunter2");

        aspect.deBefore(joinPoint);
        aspect.doAfterReturning(joinPoint, "issued-token-for-alice");

        String logged = logged();
        assertFalse(logged.contains("hunter2"));
        assertFalse(logged.contains("issued-token-for-alice"));
        assertTrue(logged.contains("[redacted]"));
    }

    @Test
    void requestLogNamesTheUserInsteadOfTheirToken() throws Throwable {
        JoinPoint joinPoint = joinPoint("list", "page=1");

        aspect.deBefore(joinPoint);
        aspect.doAfterReturning(joinPoint, "device list");

        String logged = logged();
        assertFalse(logged.contains(token));
        assertTrue(logged.contains("alice"));
        assertTrue(logged.contains("page=1"));
        assertTrue(logged.contains("device list"));
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static JoinPoint joinPoint(String methodName, Object arg) throws NoSuchMethodException {
        Method method = Endpoints.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(Endpoints.class.getName());
        when(signature.getName()).thenReturn(methodName);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{arg});
        return joinPoint;
    }

    static class Endpoints {
        @WebAspect(sensitive = true)
        void login() {
        }

        @WebAspect
        void list() {
        }
    }
}
