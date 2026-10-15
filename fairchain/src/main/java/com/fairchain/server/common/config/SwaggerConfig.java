package com.fairchain.server.common.config;

import com.fairchain.api.http.FairChainHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI — 오른쪽 위 Authorize에 고객사 API Key를 넣으면 모든 요청에 X-API-KEY 헤더가 붙는다 */
@Configuration
public class SwaggerConfig {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    public OpenAPI fairChainOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FairChain API").version("0.0.1")
                        .description("티켓 위변조 방지 · 공정 대기열 플랫폼"))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(FairChainHeaders.API_KEY)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
