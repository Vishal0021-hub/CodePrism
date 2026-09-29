package codeprism.backend.services.github;

import java.util.List;
import java.util.Map;

public interface GithubApiClient {
    List<Map<String, Object>> listUserRepos(String accessToken);
    Map<String, Object> getRepoTree(String accessToken, String owner, String repo, String branch);
    String getFileContent(String accessToken, String owner, String repo, String path);
}
