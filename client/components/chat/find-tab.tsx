"use client";

import { useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Code2,
  ExternalLink,
  FileCode,
  Loader2,
  Search,
  Sparkles,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api, type Repository, type SymbolMatch } from "@/lib/api";

export function FindTab({ repo }: { repo: Repository }) {
  const [query, setQuery] = useState("");
  const [searchedQuery, setSearchedQuery] = useState("");
  const [results, setResults] = useState<SymbolMatch[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(new Set());

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;

    setLoading(true);
    setError(null);
    setSearchedQuery(trimmed);

    try {
      const data = await api.findSymbols(repo.id, trimmed);
      setResults(data);
      // Auto-expand exact matches or first match if few results
      const initialExpanded = new Set<string>();
      data.forEach((item, idx) => {
        if (item.exactMatch || (data.length <= 3 && idx === 0)) {
          initialExpanded.add(`${item.filePath}-${item.symbolName}-${item.lineNumber ?? idx}`);
        }
      });
      setExpandedKeys(initialExpanded);
    } catch (err) {
      setError((err as Error)?.message ?? "Failed to find symbols");
      setResults(null);
    } finally {
      setLoading(false);
    }
  };

  const toggleExpand = (key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Search Bar */}
      <div className="border-b border-border/60 bg-card/30 p-4">
        <form onSubmit={handleSearch} className="mx-auto flex max-w-3xl gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search symbols, functions, methods, classes (e.g. findById, UserService)..."
              className="pl-9 pr-4 text-sm"
              autoFocus
            />
          </div>
          <Button type="submit" disabled={loading || !query.trim()} size="default">
            {loading ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                <span>Searching…</span>
              </>
            ) : (
              <>
                <Search className="size-4" />
                <span>Find</span>
              </>
            )}
          </Button>
        </form>
      </div>

      {/* Results / Empty State */}
      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto max-w-3xl space-y-4">
          {error && (
            <div className="rounded-xl border border-destructive/40 bg-destructive/10 p-4 text-sm text-destructive">
              {error}
            </div>
          )}

          {results === null && !loading && !error && (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <div className="mb-4 flex size-12 items-center justify-center rounded-2xl border border-border/60 bg-muted/40 text-muted-foreground">
                <Search className="size-6" />
              </div>
              <h4 className="font-heading text-base font-semibold">
                Direct AST &amp; Relationship Symbol Search
              </h4>
              <p className="mt-1 max-w-sm text-xs text-muted-foreground">
                Search symbols across the code graph and chunk store. Ranked exact-match-first, with zero LLM overhead.
              </p>
            </div>
          )}

          {results !== null && !loading && (
            <div>
              <div className="mb-3 flex items-center justify-between">
                <p className="text-xs font-medium text-muted-foreground">
                  Found <span className="text-foreground font-semibold">{results.length}</span>{" "}
                  result{results.length === 1 ? "" : "s"} for &ldquo;{searchedQuery}&rdquo;
                </p>
                <span className="text-[11px] text-muted-foreground">Exact matches ranked first</span>
              </div>

              {results.length === 0 ? (
                <div className="rounded-2xl border border-border/60 bg-card/40 p-8 text-center">
                  <p className="text-sm font-medium text-muted-foreground">
                    No symbols found matching &ldquo;{searchedQuery}&rdquo;
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground/70">
                    Try searching for a simpler class name, method, or symbol.
                  </p>
                </div>
              ) : (
                <div className="space-y-2.5">
                  {results.map((item, idx) => {
                    const key = `${item.filePath}-${item.symbolName}-${item.lineNumber ?? idx}`;
                    const isExpanded = expandedKeys.has(key);
                    const fileName = item.filePath ? item.filePath.split("/").pop() : "";
                    const githubUrl = item.filePath
                      ? `https://github.com/${repo.fullName}/blob/${repo.defaultBranch}/${item.filePath}${
                          item.lineNumber != null ? `#L${item.lineNumber}` : ""
                        }`
                      : null;

                    return (
                      <div
                        key={key}
                        className="group overflow-hidden rounded-xl border border-border/60 bg-card/60 transition-all duration-200 hover:border-primary/40 hover:bg-card/90"
                      >
                        {/* Header Row (Clickable) */}
                        <div
                          onClick={() => toggleExpand(key)}
                          className="flex cursor-pointer items-start justify-between gap-3 p-3.5 select-none"
                        >
                          <div className="flex min-w-0 items-start gap-2.5">
                            <div className="mt-0.5 text-muted-foreground transition-transform group-hover:text-foreground">
                              {isExpanded ? (
                                <ChevronDown className="size-4" />
                              ) : (
                                <ChevronRight className="size-4" />
                              )}
                            </div>

                            <div className="min-w-0 space-y-1">
                              <div className="flex flex-wrap items-center gap-2">
                                <span className="font-mono text-sm font-semibold text-foreground group-hover:text-primary transition-colors">
                                  {item.symbolName}
                                </span>

                                {item.exactMatch && (
                                  <Badge
                                    variant="secondary"
                                    className="inline-flex items-center gap-0.5 text-[10px] font-medium tracking-wide bg-primary/10 text-primary border-primary/20 py-0 px-1.5"
                                  >
                                    <Sparkles className="size-2.5" />
                                    Exact
                                  </Badge>
                                )}
                              </div>

                              {/* Reused citation chip style */}
                              <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
                                <Badge
                                  variant="outline"
                                  className="inline-flex items-center gap-1 text-[11px] font-mono text-muted-foreground bg-muted/30 border-border/60"
                                  title={item.filePath}
                                >
                                  <FileCode className="size-3 text-muted-foreground" />
                                  <span>{fileName}</span>
                                  {item.lineNumber != null && (
                                    <span className="text-muted-foreground">
                                      :L{item.lineNumber}
                                    </span>
                                  )}
                                </Badge>
                                <span className="text-[11px] text-muted-foreground truncate max-w-xs hidden sm:inline">
                                  {item.filePath}
                                </span>
                              </div>
                            </div>
                          </div>

                          {githubUrl && (
                            <a
                              href={githubUrl}
                              target="_blank"
                              rel="noreferrer"
                              onClick={(e) => e.stopPropagation()}
                              className="shrink-0 rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                              title="View in GitHub"
                            >
                              <ExternalLink className="size-3.5" />
                            </a>
                          )}
                        </div>

                        {/* Inline Expandable Snippet */}
                        {isExpanded && (
                          <div className="border-t border-border/50 bg-muted/20 px-3.5 pb-3.5 pt-2">
                            {item.snippet ? (
                              <pre className="overflow-x-auto rounded-lg border border-border/50 bg-background/80 p-3 font-mono text-xs leading-relaxed text-foreground/90 whitespace-pre-wrap">
                                <code>{item.snippet}</code>
                              </pre>
                            ) : (
                              <p className="text-xs italic text-muted-foreground py-1">
                                No snippet available for this symbol.
                              </p>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
