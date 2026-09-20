"use client";

import { useEffect, useRef } from "react";
import { Bot, Code2, ExternalLink, FileCode, User } from "lucide-react";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import type { ChatMessage, Citation, Repository } from "@/lib/api";

export interface ChatMessagesProps {
  repo: Repository;
  messages: ChatMessage[];
  streamText?: string;
  isLoading?: boolean;
}

export function ChatMessages({
  repo,
  messages,
  streamText = "",
  isLoading = false,
}: ChatMessagesProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamText]);

  if (isLoading) {
    return (
      <div className="flex-1 space-y-4 p-6">
        <div className="flex gap-3">
          <Skeleton className="size-8 rounded-full" />
          <div className="space-y-2 flex-1 max-w-md">
            <Skeleton className="h-4 w-1/3" />
            <Skeleton className="h-16 w-full rounded-xl" />
          </div>
        </div>
      </div>
    );
  }

  if (messages.length === 0 && !streamText) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center p-8 text-center">
        <div className="mb-4 flex size-14 items-center justify-center rounded-2xl border border-border/50 bg-card/60 shadow-sm">
          <DevPilotIcon className="size-8" />
        </div>
        <h3 className="font-heading text-lg font-semibold tracking-tight">
          Chat with {repo.fullName}
        </h3>
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">
          Ask anything about files, functions, dependencies, or architectural patterns in this repository.
        </p>

        <div className="mt-6 grid w-full max-w-lg gap-2 text-left sm:grid-cols-2">
          {[
            "Explain the project structure and main entry point",
            "What dependencies are used and how are they configured?",
            "How does authentication or authorization work?",
            "Where are the database entities and repositories defined?",
          ].map((prompt, i) => (
            <div
              key={i}
              className="rounded-xl border border-border/60 bg-card/40 p-3 text-xs text-muted-foreground transition-all duration-200 hover:border-primary/40 hover:bg-card hover:text-foreground cursor-pointer"
            >
              &ldquo;{prompt}&rdquo;
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto p-4 md:p-6 space-y-6">
      <div className="mx-auto max-w-3xl space-y-6">
        {messages.map((message) => (
          <div
            key={message.id}
            className={`flex gap-3.5 ${
              message.role === "USER" ? "flex-row-reverse" : "flex-row"
            }`}
          >
            {message.role === "USER" ? (
              <div className="flex size-8 shrink-0 select-none items-center justify-center rounded-full bg-primary text-primary-foreground shadow-xs">
                <User className="size-4" />
              </div>
            ) : (
              <div className="flex size-8 shrink-0 select-none items-center justify-center rounded-full border border-border/50 bg-card shadow-xs">
                <DevPilotIcon className="size-5" />
              </div>
            )}

            <div
              className={`flex flex-col space-y-2 max-w-[85%] sm:max-w-[75%] ${
                message.role === "USER" ? "items-end" : "items-start"
              }`}
            >
              <div
                className={`rounded-2xl px-4 py-3 text-sm leading-relaxed ${
                  message.role === "USER"
                    ? "bg-primary text-primary-foreground rounded-tr-xs"
                    : "border border-border/60 bg-card/70 backdrop-blur-xs rounded-tl-xs"
                }`}
              >
                <div className="whitespace-pre-wrap">{message.content}</div>
              </div>

              {message.citations && message.citations.length > 0 && (
                <div className="flex flex-wrap gap-1.5 pt-1">
                  {message.citations.map((cite: Citation, idx: number) => (
                    <Badge
                      key={idx}
                      variant="outline"
                      className="inline-flex items-center gap-1 text-[11px] font-mono hover:bg-muted cursor-pointer"
                      title={`${cite.filePath}${
                        cite.startLine ? `:${cite.startLine}-${cite.endLine}` : ""
                      }`}
                    >
                      <FileCode className="size-3 text-muted-foreground" />
                      <span>{cite.filePath.split("/").pop()}</span>
                      {cite.startLine && (
                        <span className="text-muted-foreground">
                          L{cite.startLine}
                        </span>
                      )}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}

        {streamText && (
          <div className="flex gap-3.5 flex-row">
            <div className="flex size-8 shrink-0 select-none items-center justify-center rounded-full border border-border/50 bg-card shadow-xs">
              <DevPilotIcon className="size-5" />
            </div>
            <div className="flex flex-col space-y-2 max-w-[85%] sm:max-w-[75%] items-start">
              <div className="rounded-2xl rounded-tl-xs border border-border/60 bg-card/70 px-4 py-3 text-sm leading-relaxed backdrop-blur-xs">
                <div className="whitespace-pre-wrap">{streamText}</div>
                <span className="inline-block size-1.5 animate-pulse rounded-full bg-primary ml-1" />
              </div>
            </div>
          </div>
        )}

        <div ref={bottomRef} />
      </div>
    </div>
  );
}
