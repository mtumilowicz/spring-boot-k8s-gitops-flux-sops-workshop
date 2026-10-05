package gitopsfluxsops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "demo")
public record DemoTokenProperties(
        @NotBlank
        @Pattern(regexp = "(?s)(?!\\p{javaWhitespace}*<to_be_replaced>\\p{javaWhitespace}*\\z).*",
                message = "must not be <to_be_replaced>")
        String token1,
        @NotBlank
        @Pattern(regexp = "(?s)(?!\\p{javaWhitespace}*<to_be_replaced>\\p{javaWhitespace}*\\z).*",
                message = "must not be <to_be_replaced>")
        String token2) {
}
