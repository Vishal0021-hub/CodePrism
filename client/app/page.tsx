"use client";

import Link from "next/link";
import { ArrowRight, GitBranch, MessageSquareCode, Sparkles } from "lucide-react";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";
import { GitHubIcon } from "@/components/icons/github-icon";
import { BrandMark } from "@/components/layouts/app-shell";
import { ModeToggle } from "@/components/ui/mode-toggle";
import { buttonVariants } from "@/components/ui/button";

import { cn } from "@/lib/utils";
import { getGithubLoginUrl } from "@/lib/api";

export default function HomePage() {
  return (
    <div className="relative min-h-svh flex flex-col overflow-hidden bg-background">
      {/* Background subtle radial gradient */}
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,oklch(from_var(--primary)_l_c_h/0.12),transparent_55%)]" />

      {/* Header */}
      <header className="relative z-10 mx-auto flex h-16 w-full max-w-5xl items-center justify-between px-4">
        <BrandMark />
        <div className="flex items-center gap-3">
          <ModeToggle />
          <Link
            href="/login"
            className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
          >
            Sign in
          </Link>
        </div>
      </header>

      {/* Hero Section */}
      <main className="relative z-10 mx-auto flex flex-1 w-full max-w-5xl flex-col justify-center gap-16 px-4 py-12 md:py-20">
        <section className="mx-auto max-w-2xl space-y-6 text-center">
          <div className="mx-auto flex size-16 items-center justify-center rounded-2xl shadow-md bg-card/60 backdrop-blur-sm border border-border/40 transition-transform duration-300 hover:scale-105">
            <DevPilotIcon className="size-12 rounded-xl" />
          </div>

          <div className="space-y-3">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium border border-primary/20 bg-primary/5 text-primary">
              <Sparkles className="size-3.5" />
              <span>AI-Powered Repository Intelligence</span>
            </div>
            <h1 className="font-heading text-4xl font-bold tracking-tight sm:text-5xl text-foreground">
              DevPilot
            </h1>
            <p className="text-lg text-muted-foreground text-balance max-w-xl mx-auto">
              Connect GitHub, index any repository, and chat with your codebase
              using retrieval-augmented answers and precise citations.
            </p>
          </div>

          <div className="flex flex-wrap items-center justify-center gap-3 pt-2">
            <a
              href={getGithubLoginUrl()}
              className={cn(
                buttonVariants({ size: "lg" }),
                "inline-flex items-center gap-2 shadow-sm transition-all duration-200 hover:shadow-md cursor-pointer"
              )}
            >
              <GitHubIcon className="size-4" />
              Continue with GitHub
              <ArrowRight className="size-4" />
            </a>
            <Link
              href="/login"
              className={cn(
                buttonVariants({ variant: "outline", size: "lg" }),
                "transition-colors duration-200"
              )}
            >
              Explore Demo
            </Link>
          </div>
        </section>

        {/* Features Grid */}
        <section className="grid gap-5 md:grid-cols-3">
          {[
            {
              title: "Connect GitHub",
              body: "Seamless OAuth login with repository permissions for public and private repositories.",
              icon: GitBranch,
            },
            {
              title: "Index with RAG",
              body: "Intelligently chunk and embed your code files into Postgres using pgvector similarity search.",
              icon: Sparkles,
            },
            {
              title: "Chat with Code",
              body: "Ask questions and get grounded answers with inline source code references and citations.",
              icon: MessageSquareCode,
            },
          ].map((item) => (
            <div
              key={item.title}
              className="group rounded-2xl border border-border/60 bg-card/70 p-6 shadow-xs backdrop-blur-md transition-all duration-300 hover:border-border hover:shadow-md hover:-translate-y-0.5"
            >
              <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary transition-colors duration-300 group-hover:bg-primary group-hover:text-primary-foreground">
                <item.icon className="size-5" />
              </div>
              <h2 className="text-base font-semibold text-foreground tracking-tight">{item.title}</h2>
              <p className="mt-2 text-sm text-muted-foreground leading-relaxed">{item.body}</p>
            </div>
          ))}
        </section>
      </main>

      {/* Footer */}
      <footer className="relative z-10 border-t border-border/30 py-6 text-center text-xs text-muted-foreground">
        DevPilot &bull; Intelligent Code Assistant
      </footer>
    </div>
  );
}