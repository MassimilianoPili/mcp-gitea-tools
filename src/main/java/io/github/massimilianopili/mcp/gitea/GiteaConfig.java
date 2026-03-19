package io.github.massimilianopili.mcp.gitea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(name = "mcp.gitea.token")
public class GiteaConfig {

    private static final Logger log = LoggerFactory.getLogger(GiteaConfig.class);

    @Value("${mcp.gitea.url:http://gitea:3000/api/v1}")
    private String baseUrl;

    @Value("${mcp.gitea.token}")
    private String token;

    @Value("${mcp.gitea.default-owner:sol_root}")
    private String defaultOwner;

    @Bean(name = "giteaWebClient")
    public WebClient giteaWebClient() {
        log.info("Gitea MCP: {} (owner: {})", baseUrl, defaultOwner);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "token " + token)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                        .build())
                .build();
    }

    public String getDefaultOwner() {
        return defaultOwner;
    }
}
