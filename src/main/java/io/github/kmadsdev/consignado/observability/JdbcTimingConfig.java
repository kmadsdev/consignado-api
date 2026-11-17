package io.github.kmadsdev.consignado.observability;

import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the pooled {@link DataSource} (Hikari) in a datasource-proxy so that every JDBC
 * call made by a request is measured. A {@link BeanPostProcessor} keeps the original bean
 * definition: Flyway, JPA, health checks and pool metrics get the same pool through the
 * proxy, which forwards {@code unwrap} to it.
 */
@Configuration(proxyBeanMethods = false)
class JdbcTimingConfig {

    @Bean
    static BeanPostProcessor jdbcTimingDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
                    return wrap(dataSource);
                }
                return bean;
            }
        };
    }

    static ProxyDataSource wrap(DataSource dataSource) {
        JdbcTimingListener listener = new JdbcTimingListener();
        return ProxyDataSourceBuilder.create("consignado", dataSource)
                .listener(listener)
                .methodListener(listener)
                .build();
    }
}
