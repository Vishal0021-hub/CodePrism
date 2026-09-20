"use client";

import { useState, useRef, useEffect, KeyboardEvent } from "react";
import { ArrowUp, Square } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

export interface ChatComposerProps {
  disabled?: boolean;
  streaming?: boolean;
  onSend: (message: string) => void;
  onStop: () => void;
}

export function ChatComposer({
  disabled = false,
  streaming = false,
  onSend,
  onStop,
}: ChatComposerProps) {
  const [input, setInput] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!disabled && textareaRef.current) {
      textareaRef.current.focus();
    }
  }, [disabled]);

  const handleSubmit = () => {
    const trimmed = input.trim();
    if (!trimmed || disabled || streaming) return;
    onSend(trimmed);
    setInput("");
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  return (
    <div className="border-t border-border/50 bg-background/80 p-4 backdrop-blur-md">
      <div className="relative mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border border-border/60 bg-card/60 p-2 shadow-sm focus-within:border-primary/50 focus-within:ring-1 focus-within:ring-primary/30">
        <Textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={
            disabled
              ? "Select or create a chat session to start..."
              : "Ask a question about this repository... (Enter to send, Shift+Enter for new line)"
          }
          disabled={disabled}
          rows={1}
          className="min-h-[44px] max-h-36 resize-none border-0 bg-transparent px-3 py-2 text-sm shadow-none focus-visible:ring-0"
        />

        {streaming ? (
          <Button
            type="button"
            size="icon"
            variant="destructive"
            onClick={onStop}
            className="size-9 shrink-0 rounded-xl"
            title="Stop generating"
          >
            <Square className="size-4 fill-current" />
          </Button>
        ) : (
          <Button
            type="button"
            size="icon"
            disabled={disabled || !input.trim()}
            onClick={handleSubmit}
            className="size-9 shrink-0 rounded-xl"
            title="Send message"
          >
            <ArrowUp className="size-4" />
          </Button>
        )}
      </div>
    </div>
  );
}
