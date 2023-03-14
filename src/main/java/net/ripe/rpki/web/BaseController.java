package net.ripe.rpki.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Value;
import net.ripe.rpki.server.api.configuration.Environment;
import net.ripe.rpki.server.api.configuration.RepositoryConfiguration;
import net.ripe.rpki.server.api.services.background.BackgroundService;
import net.ripe.rpki.server.api.services.system.ActiveNodeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ModelAttribute;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BaseController {

    public static final String ADMIN_HOME = "/admin";
    protected final RepositoryConfiguration repositoryConfiguration;
    protected final ActiveNodeService activeNodeService;

    public BaseController(RepositoryConfiguration repositoryConfiguration, ActiveNodeService activeNodeService) {
        this.repositoryConfiguration = repositoryConfiguration;
        this.activeNodeService = activeNodeService;
    }

    @ModelAttribute(name = "currentUser", binding = false)
    public UserData currentUser(@AuthenticationPrincipal Object user) {
        if (user instanceof OAuth2User) {
            OAuth2User oAuth2User = (OAuth2User) user;
            return new UserData(oAuth2User.getName(), oAuth2User.getAttribute("name"), oAuth2User.getAttribute("email"));
        } else {
            String id = String.valueOf(user);
            return new UserData(id, id, null);
        }
    }

    @ModelAttribute(name = "coreConfiguration", binding = false)
    public CoreConfigurationData coreConfigurationData() {
        return new CoreConfigurationData(repositoryConfiguration, activeNodeService);
    }

    @Value
    public static class UserData {
        String id;
        String name;
        String email;
    }

    @Value
    @AllArgsConstructor
    public static class CoreConfigurationData {
        String localRepositoryDirectory;
        String publicRepositoryUri;
        String activeNodeName;
        String currentNodeName;
        String environment;

        CoreConfigurationData(RepositoryConfiguration repositoryConfiguration, ActiveNodeService activeNodeService) {
            this.localRepositoryDirectory = repositoryConfiguration.getLocalRepositoryDirectory().getAbsolutePath();
            this.publicRepositoryUri = repositoryConfiguration.getPublicRepositoryUri().toASCIIString();
            this.activeNodeName = activeNodeService.getActiveNodeName();
            this.currentNodeName = activeNodeService.getCurrentNodeName();
            this.environment = Environment.getEnvironmentName();
        }
    }

    @Value
    @AllArgsConstructor
    public static class BackgroundServiceData {
        String id;
        String name;
        String status;
        boolean active;
        boolean waitingOrRunning;
        Map<String, String> supportedParameters;

        BackgroundServiceData(String id, BackgroundService backgroundService) {
            this.id = id;
            this.name = backgroundService.getName();
            this.status = backgroundService.getStatus();
            this.active = backgroundService.isActive();
            this.waitingOrRunning = backgroundService.isWaitingOrRunning();
            this.supportedParameters = backgroundService.supportedParameters();
        }

        static List<BackgroundServiceData> fromBackgroundServices(Map<String, BackgroundService> backgroundServiceMap) {
            return backgroundServiceMap.entrySet().stream()
                .map(entry -> new BackgroundServiceData(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(s -> s.name))
                .collect(Collectors.toList());
        }
    }

    @Data
    @AllArgsConstructor
    public static class ActiveNodeForm {
        @NotBlank
        // Regex taken from https://stackoverflow.com/a/106223
        @Pattern(
            regexp = "\\s*(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])\\s*",
            message = "must be a valid hostname"
        )
        String name;
    }
}
