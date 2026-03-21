package io.github.massimilianopili.mcp.gitea;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "mcp.gitea.token")
public class GiteaTools {

    private static final Logger log = LoggerFactory.getLogger(GiteaTools.class);

    private final WebClient client;
    private final GiteaConfig config;

    public GiteaTools(@Qualifier("giteaWebClient") WebClient client, GiteaConfig config) {
        this.client = client;
        this.config = config;
    }

    private String owner(String owner) {
        return (owner == null || owner.isBlank()) ? config.getDefaultOwner() : owner;
    }

    // ─── Repository CRUD ───────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_repos",
            description = "List repositories for a Gitea user. Returns name, description, visibility, clone URLs.")
    public Mono<List<Map<String, Object>>> listRepos(
            @ToolParam(description = "Repository owner (default: configured owner)", required = false) String owner) {
        return client.get()
                .uri("/repos/search?owner={owner}&limit=50", owner(owner))
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object data = resp.get("data");
                    if (data instanceof List<?> list) {
                        return list.stream()
                                .filter(Map.class::isInstance)
                                .<Map<String, Object>>map(r -> {
                                    Map<?, ?> repo = (Map<?, ?>) r;
                                    return mapOf(
                                            "name", str(repo, "name"),
                                            "description", str(repo, "description"),
                                            "private", Boolean.TRUE.equals(repo.get("private")),
                                            "ssh_url", str(repo, "ssh_url"),
                                            "html_url", str(repo, "html_url")
                                    );
                                })
                                .toList();
                    }
                    return List.<Map<String, Object>>of();
                })
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    @ReactiveTool(name = "gitea_create_repo",
            description = "Creates a new Gitea repository. Without owner, creates under the authenticated user. "
                        + "With owner, creates under that organization (e.g. 'maven-libs').")
    public Mono<Map<String, Object>> createRepo(
            @ToolParam(description = "Repository name") String name,
            @ToolParam(description = "Repository description", required = false) String description,
            @ToolParam(description = "Private repository (default: false)", required = false) Boolean isPrivate,
            @ToolParam(description = "Organization name to create under (e.g. 'maven-libs'). Omit for personal repo.", required = false) String owner) {
        Map<String, Object> body = mapOf(
                "name", name,
                "description", description != null ? description : "",
                "private", isPrivate != null ? isPrivate : false,
                "auto_init", false
        );
        String uri = (owner != null && !owner.isBlank())
                ? "/orgs/" + owner + "/repos"
                : "/user/repos";
        return client.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(repo -> mapOf(
                        "name", str(repo, "name"),
                        "ssh_url", str(repo, "ssh_url"),
                        "html_url", str(repo, "html_url"),
                        "clone_url", str(repo, "clone_url")
                ))
                .onErrorResume(e -> Mono.just(mapOf("error", e.getMessage())));
    }

    @ReactiveTool(name = "gitea_delete_repo",
            description = "Delete a Gitea repository. This action is irreversible.")
    public Mono<String> deleteRepo(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.delete()
                .uri("/repos/{owner}/{repo}", owner(owner), repo)
                .retrieve()
                .toBodilessEntity()
                .map(r -> "Deleted: " + owner(owner) + "/" + repo)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()));
    }

    @ReactiveTool(name = "gitea_get_repo",
            description = "Get details of a Gitea repository: size, stars, forks, default branch, timestamps.")
    public Mono<Map<String, Object>> getRepo(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.get()
                .uri("/repos/{owner}/{repo}", owner(owner), repo)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("name", str(resp, "name"));
                    result.put("description", str(resp, "description"));
                    result.put("private", resp.getOrDefault("private", false));
                    result.put("default_branch", str(resp, "default_branch"));
                    result.put("stars_count", resp.getOrDefault("stars_count", 0));
                    result.put("forks_count", resp.getOrDefault("forks_count", 0));
                    result.put("size", resp.getOrDefault("size", 0));
                    result.put("ssh_url", str(resp, "ssh_url"));
                    result.put("html_url", str(resp, "html_url"));
                    result.put("created_at", str(resp, "created_at"));
                    result.put("updated_at", str(resp, "updated_at"));
                    return result;
                })
                .onErrorResume(e -> Mono.just(mapOf("error", e.getMessage())));
    }

    // ─── Branches ──────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_branches",
            description = "List branches of a Gitea repository.")
    public Mono<List<Map<String, Object>>> listBranches(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.get()
                .uri("/repos/{owner}/{repo}/branches", owner(owner), repo)
                .retrieve()
                .bodyToFlux(Map.class)
                .map(b -> mapOf(
                        "name", str(b, "name"),
                        "protected", b.getOrDefault("protected", false)
                ))
                .collectList()
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    // ─── Files ─────────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_get_file",
            description = "Read a file from a Gitea repository. Returns decoded content.")
    public Mono<String> getFile(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "File path (e.g. README.md)") String path,
            @ToolParam(description = "Branch or commit ref (default: main)", required = false) String ref) {
        String uri = ref != null && !ref.isBlank()
                ? "/repos/{owner}/{repo}/raw/{path}?ref={ref}"
                : "/repos/{owner}/{repo}/raw/{path}";
        return client.get()
                .uri(uri, owner(owner), repo, path, ref != null ? ref : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()));
    }

    // ─── Releases ──────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_releases",
            description = "List releases of a Gitea repository.")
    public Mono<List<Map<String, Object>>> listReleases(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.get()
                .uri("/repos/{owner}/{repo}/releases?limit=20", owner(owner), repo)
                .retrieve()
                .bodyToFlux(Map.class)
                .map(r -> mapOf(
                        "tag_name", str(r, "tag_name"),
                        "name", str(r, "name"),
                        "draft", r.getOrDefault("draft", false),
                        "prerelease", r.getOrDefault("prerelease", false),
                        "created_at", str(r, "created_at")
                ))
                .collectList()
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    @ReactiveTool(name = "gitea_create_release",
            description = "Create a new release for a Gitea repository.")
    public Mono<Map<String, Object>> createRelease(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Tag name (e.g. v1.0.0)") String tag,
            @ToolParam(description = "Release title") String title,
            @ToolParam(description = "Release body/notes", required = false) String body) {
        Map<String, Object> payload = mapOf(
                "tag_name", tag,
                "name", title,
                "body", body != null ? body : "",
                "draft", false,
                "prerelease", false
        );
        return client.post()
                .uri("/repos/{owner}/{repo}/releases", owner(owner), repo)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> mapOf(
                        "id", r.getOrDefault("id", 0),
                        "tag_name", str(r, "tag_name"),
                        "html_url", str(r, "html_url")
                ))
                .onErrorResume(e -> Mono.just(mapOf("error", e.getMessage())));
    }

    // ─── Tags ──────────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_tags",
            description = "List tags of a Gitea repository.")
    public Mono<List<Map<String, Object>>> listTags(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.get()
                .uri("/repos/{owner}/{repo}/tags?limit=50", owner(owner), repo)
                .retrieve()
                .bodyToFlux(Map.class)
                .map(t -> mapOf(
                        "name", str(t, "name"),
                        "id", str(t, "id")
                ))
                .collectList()
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    @ReactiveTool(name = "gitea_create_tag",
            description = "Create a git tag on a Gitea repository. Can trigger CI/CD workflows on tag patterns.")
    public Mono<Map<String, Object>> createTag(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Tag name (e.g. v1.0.0)") String tag,
            @ToolParam(description = "Tag message (creates annotated tag)", required = false) String message,
            @ToolParam(description = "Target branch or commit SHA (default: default branch)", required = false) String target) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tag_name", tag);
        if (message != null && !message.isBlank()) payload.put("message", message);
        if (target != null && !target.isBlank()) payload.put("target", target);
        return client.post()
                .uri("/repos/{owner}/{repo}/tags", owner(owner), repo)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(t -> mapOf(
                        "name", str(t, "name"),
                        "id", str(t, "id")
                ))
                .onErrorResume(e -> Mono.just(mapOf("error", e.getMessage())));
    }

    @ReactiveTool(name = "gitea_delete_tag",
            description = "Delete a tag from a Gitea repository.")
    public Mono<String> deleteTag(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Tag name to delete") String tag) {
        return client.delete()
                .uri("/repos/{owner}/{repo}/tags/{tag}", owner(owner), repo, tag)
                .retrieve()
                .toBodilessEntity()
                .map(r -> "Deleted tag: " + tag)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()));
    }

    // ─── Secrets ───────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_set_secret",
            description = "Set or update an Actions secret on a Gitea repository.")
    public Mono<String> setSecret(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Secret name") String secretName,
            @ToolParam(description = "Secret value") String value) {
        return client.put()
                .uri("/repos/{owner}/{repo}/actions/secrets/{secret}", owner(owner), repo, secretName)
                .bodyValue(Map.of("data", value))
                .retrieve()
                .toBodilessEntity()
                .map(r -> "Secret set: " + secretName)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()));
    }

    // ─── Tokens ────────────────────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_tokens",
            description = "List API tokens for the authenticated Gitea user.")
    public Mono<List<Map<String, Object>>> listTokens() {
        return client.get()
                .uri("/user/tokens")
                .retrieve()
                .bodyToFlux(Map.class)
                .map(t -> mapOf(
                        "id", t.getOrDefault("id", 0),
                        "name", str(t, "name")
                ))
                .collectList()
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    // ─── Workflows / Actions ───────────────────────────────────────────────────

    @ReactiveTool(name = "gitea_list_workflow_runs",
            description = "List recent workflow runs (Actions) for a Gitea repository.")
    public Mono<List<Map<String, Object>>> listWorkflowRuns(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        return client.get()
                .uri("/repos/{owner}/{repo}/actions/runs?limit=20", owner(owner), repo)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object data = resp.get("workflow_runs");
                    if (data instanceof List<?> list) {
                        return list.stream()
                                .filter(Map.class::isInstance)
                                .<Map<String, Object>>map(r -> {
                                    Map<?, ?> run = (Map<?, ?>) r;
                                    return mapOf(
                                            "id", run.get("id") != null ? run.get("id") : 0,
                                            "name", str(run, "name"),
                                            "status", str(run, "status"),
                                            "conclusion", str(run, "conclusion"),
                                            "event", str(run, "event"),
                                            "created_at", str(run, "created_at")
                                    );
                                })
                                .toList();
                    }
                    return List.<Map<String, Object>>of();
                })
                .onErrorResume(e -> Mono.just(List.of(mapOf("error", e.getMessage()))));
    }

    @ReactiveTool(name = "gitea_get_workflow_run",
            description = "Get details and jobs of a specific workflow run.")
    public Mono<Map<String, Object>> getWorkflowRun(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Workflow run ID") Long runId) {
        return client.get()
                .uri("/repos/{owner}/{repo}/actions/runs/{id}", owner(owner), repo, runId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> (Map<String, Object>) r)
                .onErrorResume(e -> Mono.just(mapOf("error", e.getMessage())));
    }

    @ReactiveTool(name = "gitea_trigger_workflow",
            description = "Manually trigger a workflow (dispatch event) on a Gitea repository.")
    public Mono<String> triggerWorkflow(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Workflow filename (e.g. deploy.yml)") String workflow,
            @ToolParam(description = "Branch or tag ref (default: main)", required = false) String ref) {
        String targetRef = ref != null && !ref.isBlank() ? ref : "main";
        return client.post()
                .uri("/repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches",
                        owner(owner), repo, workflow)
                .bodyValue(Map.of("ref", targetRef))
                .retrieve()
                .toBodilessEntity()
                .map(r -> "Workflow triggered: " + workflow + " on " + targetRef)
                .onErrorResume(e -> Mono.just("ERROR: " + e.getMessage()));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
