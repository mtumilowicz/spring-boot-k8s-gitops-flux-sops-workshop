package gitopsfluxsops;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class StartupTokenLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupTokenLogger.class);

    private final DemoTokenProperties properties;
    private final Environment environment;

    StartupTokenLogger(DemoTokenProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String activeProfiles = Arrays.toString(environment.getActiveProfiles());

        /*
         * Workshop-only behavior.
         * Production applications must not log secrets. This project logs the tokens
         * only to make the Kubernetes Secret -> container environment variable ->
         * Spring property binding path directly observable during startup.
         */
        log.info("WORKSHOP_TOKEN_CHECK activeProfiles={}", activeProfiles);
        log.info("WORKSHOP_TOKEN_CHECK demo.token1={}", properties.token1());
        log.info("WORKSHOP_TOKEN_CHECK demo.token2={}", properties.token2());
    }
}
