"use client";

import { GitBranch, MessageSquare, Plus, CheckCircle2, Loader2, AlertCircle, Clock } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useChatSessions, useCreateChatSession } from "@/hooks/use-chat";
import type { Repository } from "@/lib/api";

export interface ChatSidebarProps {
  repo: Repository;
  sessionId: string | null;
  onSelectSession: (sessionId: string) => void;
}

export function ChatSidebar({
  repo,
  sessionId,
  onSelectSession,
}: ChatSidebarProps) {
  const sessionsQuery = useChatSessions(repo.id);
  const createSession = useCreateChatSession(repo.id);

  const getStatusBadge = () => {
    switch (repo.indexStatus) {
      case "READY":
        return (
          <Badge variant="outline" className="gap-1 border-emerald-500/30 text-emerald-500 bg-emerald-500/5">
            <CheckCircle2 className="size-3" />
            Ready
          </Badge>
        );
      case "INDEXING":
        return (
          <Badge variant="outline" className="gap-1 border-amber-500/30 text-amber-500 bg-amber-500/5">
            <Loader2 className="size-3 animate-spin" />
            Indexing
          </Badge>
        );
      case "FAILED":
        return (
          <Badge variant="outline" className="gap-1 border-destructive/30 text-destructive bg-destructive/5">
            <AlertCircle className="size-3" />
            Failed
          </Badge>
        );
      default:
        return (
          <Badge variant="outline" className="gap-1 text-muted-foreground">
            <Clock className="size-3" />
            Pending
          </Badge>
        );
    }
  };

  return (
    <aside className="flex w-full flex-col border-b border-border/50 bg-card/30 md:w-72 md:border-r md:border-b-0">
      {/* Repo Summary */}
      <div className="border-b border-border/40 p-4 space-y-3">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h2 className="truncate text-sm font-semibold tracking-tight text-foreground">
              {repo.name}
            </h2>
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground mt-0.5">
              <GitBranch className="size-3 shrink-0" />
              <span className="truncate">{repo.defaultBranch}</span>
            </div>
          </div>
          {getStatusBadge()}
        </div>

        <div className="grid grid-cols-2 gap-2 text-xs">
          <div className="rounded-lg border border-border/40 bg-background/50 p-2 text-center">
            <span className="text-muted-foreground">Files</span>
            <p className="font-semibold text-foreground mt-0.5">
              {repo.filesTotal > 0 ? `${repo.filesProcessed}/${repo.filesTotal}` : "—"}
            </p>
          </div>
          <div className="rounded-lg border border-border/40 bg-background/50 p-2 text-center">
            <span className="text-muted-foreground">Chunks</span>
            <p className="font-semibold text-foreground mt-0.5">
              {repo.chunkCount > 0 ? repo.chunkCount : "—"}
            </p>
          </div>
        </div>
      </div>

      {/* Session Actions */}
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          Conversations
        </span>
        <Button
          variant="ghost"
          size="icon"
          className="size-7 rounded-lg"
          onClick={() => createSession.mutate()}
          disabled={createSession.isPending || repo.indexStatus !== "READY"}
          title="Start new conversation"
        >
          <Plus className="size-4" />
        </Button>
      </div>

      {/* Session List */}
      <div className="flex-1 overflow-y-auto px-2 pb-4 space-y-1">
        {sessionsQuery.data?.map((session) => {
          const isActive = session.id === sessionId;
          return (
            <button
              key={session.id}
              type="button"
              onClick={() => onSelectSession(session.id)}
              className={`flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-xs transition-colors duration-150 ${
                isActive
                  ? "bg-primary/10 font-medium text-primary"
                  : "text-muted-foreground hover:bg-muted/60 hover:text-foreground"
              }`}
            >
              <MessageSquare className="size-3.5 shrink-0" />
              <span className="truncate flex-1">{session.title || "Chat Session"}</span>
            </button>
          );
        })}

        {(!sessionsQuery.data || sessionsQuery.data.length === 0) && (
          <p className="p-4 text-center text-xs text-muted-foreground">
            No conversations yet
          </p>
        )}
      </div>
    </aside>
  );
}
