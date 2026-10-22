package com.ecclesiaflow.platform.moderation.autoconfigure;

import com.ecclesiaflow.platform.moderation.InvalidTermListException;
import com.ecclesiaflow.platform.moderation.TermFilter;
import com.ecclesiaflow.platform.moderation.TermFilterLoader;
import com.ecclesiaflow.platform.moderation.TermFilterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * Publishes the {@link TermFilter} once {@code ecclesiaflow.moderation.terms-file} is present, so a module that
 * has not adopted the filter is untouched. Past that point there is no quiet fallback: a blank path, a missing
 * file or a malformed line stops the startup, and a module that injects the filter without the property fails
 * on the missing bean.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = TermFilterProperties.PREFIX, name = "terms-file")
@EnableConfigurationProperties(TermFilterProperties.class)
public class PlatformTermFilterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TermFilter termFilter(TermFilterProperties properties) {
        String location = properties.getTermsFile();
        if (!StringUtils.hasText(location)) {
            throw InvalidTermListException.notConfigured();
        }
        TermFilter filter = TermFilterLoader.load(Path.of(location.strip()));
        if (filter.size() == 0) {
            log.warn("MODERATION: ⚠ the term list is empty, no text will be rejected");
        } else {
            log.info("MODERATION: term filter active, {} term(s) loaded", filter.size());
        }
        return filter;
    }
}
