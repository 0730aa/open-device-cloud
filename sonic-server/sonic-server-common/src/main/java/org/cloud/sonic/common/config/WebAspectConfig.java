package org.cloud.sonic.common.config;

import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.cloud.sonic.common.tools.JWTTokenTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * @author ZhouYiXun
 * @des AOP框架搭配注解类，生成对应web请求日志
 * @date 2021/8/15 18:26
 */
@Aspect
@Component
public class WebAspectConfig {
    private static final String REDACTED = "[redacted]";
    private final Logger logger = LoggerFactory.getLogger(WebAspectConfig.class);
    @Autowired
    private JWTTokenTool jwtTokenTool;

    /**
     * @return void
     * @author ZhouYiXun
     * @des 定义切点，注解类webAspect
     * @date 2021/8/15 23:08
     */
    @Pointcut("@annotation(WebAspect)")
    public void webAspect() {
    }

    /**
     * @param joinPoint
     * @return void
     * @author ZhouYiXun
     * @des 请求前获取所有信息
     * @date 2021/8/15 23:08
     */
    @Before("webAspect()")
    public void deBefore(JoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        //默认打印为json格式，接入elasticsearch等会方便查看
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("url", request.getRequestURL().toString());
        jsonObject.put("method", request.getMethod());
        // Log who made the request, never the bearer token itself.
        String token = request.getHeader("SonicToken");
        jsonObject.put("user", token == null ? null : jwtTokenTool.getUserName(token));
        jsonObject.put("class", joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName());
        jsonObject.put("request", isSensitive(joinPoint) ? REDACTED : Arrays.toString(joinPoint.getArgs()));
        logger.info(jsonObject.toJSONString());
    }

    /**
     * @param ret
     * @return void
     * @author ZhouYiXun
     * @des 请求完毕后打印结果
     * @date 2021/8/15 23:10
     */
    @AfterReturning(returning = "ret", pointcut = "webAspect()")
    public void doAfterReturning(JoinPoint joinPoint, Object ret) throws Throwable {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("response", isSensitive(joinPoint) ? REDACTED : ret);
        logger.info(jsonObject.toJSONString());
    }

    /**
     * @param joinPoint
     * @param ex
     * @return void
     * @author ZhouYiXun
     * @des 报错的话打印错误信息
     * @date 2021/8/15 23:11
     */
    @AfterThrowing(throwing = "ex", pointcut = "webAspect()")
    public void error(JoinPoint joinPoint, Exception ex) {
        logger.info("error : " + ex.getMessage());
    }

    private static boolean isSensitive(JoinPoint joinPoint) {
        WebAspect webAspect = ((MethodSignature) joinPoint.getSignature()).getMethod().getAnnotation(WebAspect.class);
        return webAspect != null && webAspect.sensitive();
    }
}