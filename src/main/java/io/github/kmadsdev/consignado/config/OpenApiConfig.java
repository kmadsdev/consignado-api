package io.github.kmadsdev.consignado.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI consignadoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Consignado API")
                .version("v1")
                .description("Payroll-deducted loans: agreements, customers, simulations and contracts. "
                        + "Errors use problem details (application/problem+json). Study project."));
    }
}
