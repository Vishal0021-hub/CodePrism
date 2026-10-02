package codeprism.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import codeprism.backend.dto.FindResultDto;
import codeprism.backend.security.CurrentUser;
import codeprism.backend.services.FindService;
import codeprism.backend.services.RepoService;

@RestController
@RequestMapping("/api/repos")
public class FindController {

    private final CurrentUser currentUser;
    private final RepoService repoService;
    private final FindService findService;

    public FindController(
            CurrentUser currentUser,
            RepoService repoService,
            FindService findService) {
        this.currentUser = currentUser;
        this.repoService = repoService;
        this.findService = findService;
    }

    @GetMapping("/{id}/find")
    public List<FindResultDto> find(
            @PathVariable("id") UUID id,
            @RequestParam("symbol") String symbol) {
        UUID userId = currentUser.require().getId();
        repoService.requireOwned(id, userId);
        return findService.findSymbols(id, symbol);
    }
}
