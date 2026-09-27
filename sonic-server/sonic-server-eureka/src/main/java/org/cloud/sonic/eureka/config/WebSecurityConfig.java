package org.cloud.sonic.eureka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @author ZhouYiXun
 * @des 配置需要加密的内容
 * @date 2021/8/19 15:26
 */
@Configuration
public class WebSecurityConfig {
    static final String REGISTRY_PATHS = "/eureka/**";

    /**
     * The registry API needs the SONIC_EUREKA_USERNAME / SONIC_EUREKA_PASSWORD credentials that
     * every server component already puts in its registry URL. Without them, anyone who could
     * reach this port could register an instance of sonic-server-controller, which the gateway
     * would then send users' logins and tokens, and agents' keys, to. Only the health check
     * stays open, for container orchestration.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                // Registry clients authenticate with HTTP Basic and send no CSRF token.
                .csrf(csrf -> csrf.ignoringRequestMatchers(REGISTRY_PATHS))
                .httpBasic(Customizer.withDefaults())
                // The dashboard keeps its login page.
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
