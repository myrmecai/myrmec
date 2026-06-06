package ai.myrmec.engine._system.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation configuration for Myrmec Control Engine.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI myrmecOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Myrmec Control Engine API")
                        .description("REST API for the Myrmec Distributed AI Platform Control Plane")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("Myrmec Team")
                                .url("https://myrmec.ai"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token obtained from /api/v1/agent/auth/register (agents) or /api/v1/auth/login (users)")));
    }
}
