package gitopsfluxsops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DemoTokenPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void rejectsMissingValues() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Test
    void rejectsBlankValues() {
        runner.withPropertyValues("demo.token1=", "demo.token2= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void rejectsMarkerValues() {
        runner.withPropertyValues("demo.token1=<to_be_replaced>", "demo.token2= <to_be_replaced>\t")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void bindsValidValues() {
        runner.withPropertyValues("demo.token1=valid-token1", "demo.token2=valid-token2")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DemoTokenProperties.class);
                    assertThat(context.getBean(DemoTokenProperties.class))
                            .isEqualTo(new DemoTokenProperties("valid-token1", "valid-token2"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DemoTokenProperties.class)
    static class PropertiesConfiguration {}
}
