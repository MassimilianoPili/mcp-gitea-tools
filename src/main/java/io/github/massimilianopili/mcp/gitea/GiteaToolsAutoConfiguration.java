package io.github.massimilianopili.mcp.gitea;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.gitea.token")
@Import({GiteaConfig.class, GiteaTools.class})
public class GiteaToolsAutoConfiguration {
}
