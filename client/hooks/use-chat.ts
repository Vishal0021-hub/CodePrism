"use client";

import { useState, useRef, useCallback } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api, type ChatMessage, type ChatSession, getApiBaseUrl } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { toast } from "@/components/ui/toast";

export function useChatSessions(repositoryId: string, enabled = true) {
  return useQuery<ChatSession[]>({
    queryKey: queryKeys.chat.sessions(repositoryId),
    queryFn: async () => {
      try {
        return await api.listSessions(repositoryId);
      } catch {
        // Fallback to local session if backend chat endpoints not yet enabled
        return [
          {
            id: `session-default-${repositoryId}`,
            repositoryId,
            title: "Default Conversation",
            createdAt: new Date().toISOString(),
          },
        ];
      }
    },
    enabled: Boolean(repositoryId) && enabled,
    staleTime: 60_000,
  });
}

export function useCreateChatSession(repositoryId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (title?: string) => {
      try {
        return await api.createSession(repositoryId, title ?? "New Conversation");
      } catch {
        // Local fallback session
        const fallbackSession: ChatSession = {
          id: `session-${Date.now()}`,
          repositoryId,
          title: title ?? "New Conversation",
          createdAt: new Date().toISOString(),
        };
        return fallbackSession;
      }
    },
    onSuccess: (newSession) => {
      queryClient.setQueryData<ChatSession[]>(
        queryKeys.chat.sessions(repositoryId),
        (old) => (old ? [newSession, ...old] : [newSession])
      );
    },
  });
}

export function useChatMessages(sessionId: string | null) {
  return useQuery<ChatMessage[]>({
    queryKey: sessionId ? queryKeys.chat.messages(sessionId) : ["chat", "messages", "none"],
    queryFn: async () => {
      if (!sessionId) return [];
      try {
        return await api.getMessages(sessionId);
      } catch {
        return [];
      }
    },
    enabled: Boolean(sessionId),
  });
}

export function useStreamChat(sessionId: string | null) {
  const queryClient = useQueryClient();
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState("");
  const abortControllerRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setStreaming(false);
  }, []);

  const send = useCallback(
    async (content: string) => {
      if (!sessionId || !content.trim()) return;

      const userMsg: ChatMessage = {
        id: `msg-${Date.now()}`,
        role: "USER",
        content: content.trim(),
        citations: [],
        createdAt: new Date().toISOString(),
      };

      // Optimistically append user message
      queryClient.setQueryData<ChatMessage[]>(
        queryKeys.chat.messages(sessionId),
        (prev = []) => [...prev, userMsg]
      );

      setStreaming(true);
      setStreamText("");

      const controller = new AbortController();
      abortControllerRef.current = controller;

      try {
        const response = await fetch(`${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ content }),
          signal: controller.signal,
          credentials: "include",
        });

        if (!response.ok || !response.body) {
          throw new Error("Chat stream unavailable");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let accumulated = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split("\n\n");
          buffer = parts.pop() ?? "";

          for (const part of parts) {
            if (!part.trim()) continue;

            const lines = part.split("\n");
            let event = "message";
            const dataLines: string[] = [];

            for (const line of lines) {
              if (line.startsWith("event:")) {
                event = line.slice(6).trim();
              } else if (line.startsWith("data:")) {
                dataLines.push(line.slice(5).trimStart());
              }
            }

            const data = dataLines.join("\n");
            if (!data) continue;

            try {
              if (event === "token") {
                let token = data;
                try {
                  const parsed = JSON.parse(data);
                  if (typeof parsed === "string") token = parsed;
                } catch {
                  // use raw data if not JSON formatted
                }
                accumulated += token;
                setStreamText(accumulated);
              } else if (event === "assistant_message") {
                const msg = JSON.parse(data) as ChatMessage;
                // Replace the streaming text with the final message
                queryClient.setQueryData<ChatMessage[]>(
                  queryKeys.chat.messages(sessionId),
                  (prev = []) => [...prev, msg]
                );
                accumulated = "";
              } else if (event === "done") {
                // Stream complete
              }
            } catch {
              // ignore parse errors for individual events
            }
          }
        }

        // If we accumulated text but never got an assistant_message event,
        // create a synthetic assistant message
        if (accumulated) {
          const assistantMsg: ChatMessage = {
            id: `msg-ai-${Date.now()}`,
            role: "ASSISTANT",
            content: accumulated,
            citations: [],
            createdAt: new Date().toISOString(),
          };
          queryClient.setQueryData<ChatMessage[]>(
            queryKeys.chat.messages(sessionId),
            (prev = []) => [...prev, assistantMsg]
          );
        }
      } catch (err: unknown) {
        if ((err as Error)?.name !== "AbortError") {
          const errorMsg: ChatMessage = {
            id: `msg-err-${Date.now()}`,
            role: "ASSISTANT",
            content:
              "Sorry, I couldn't connect to the chat service. Please ensure the backend is running and the Gemini API key is configured.",
            citations: [],
            createdAt: new Date().toISOString(),
          };
          queryClient.setQueryData<ChatMessage[]>(
            queryKeys.chat.messages(sessionId),
            (prev = []) => [...prev, errorMsg]
          );
        }
      } finally {
        setStreaming(false);
        setStreamText("");
        abortControllerRef.current = null;
      }
    },
    [sessionId, queryClient]
  );

  return { send, stop, streaming, streamText };
}
