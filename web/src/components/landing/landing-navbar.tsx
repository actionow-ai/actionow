"use client";

import { useSyncExternalStore } from "react";
import { useTranslations, useLocale } from "next-intl";
import Link from "next/link";
import NextImage from "next/image";
import { Button } from "@heroui/react";
import { Sun, Moon, Github } from "lucide-react";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { useTheme } from "@/components/providers/theme-provider";

const GITHUB_REPO_URL = "https://github.com/actionow-ai/actionow";

const emptySubscribe = () => () => {};
function useMounted() {
  return useSyncExternalStore(emptySubscribe, () => true, () => false);
}

function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const mounted = useMounted();

  if (!mounted) return <div className="size-9" />;

  return (
    <Button
      isIconOnly
      variant="ghost"
      size="sm"
      onPress={() => setTheme(theme === "dark" ? "light" : "dark")}
      aria-label="Toggle theme"
    >
      {theme === "dark" ? (
        <Sun className="size-4" />
      ) : (
        <Moon className="size-4" />
      )}
    </Button>
  );
}

export function BrandLogo() {
  const { resolvedTheme } = useTheme();
  const mounted = useMounted();

  const src =
    mounted && resolvedTheme === "dark"
      ? "/full-logo-dark.png"
      : "/full-logo.png";

  return (
    <NextImage
      key={src}
      src={src}
      alt="ActioNow"
      width={140}
      height={32}
      priority
    />
  );
}

export function Navbar() {
  const t = useTranslations("landing.nav");
  const locale = useLocale();

  return (
    <header className="pointer-events-auto fixed top-0 right-0 left-0 z-50 flex items-center justify-between border-b border-separator/20 bg-background/60 px-6 py-4 backdrop-blur-xl md:px-10">
      <Link href={`/${locale}`} className="flex items-center">
        <BrandLogo />
      </Link>
      <div className="flex items-center gap-8">
        <nav className="hidden gap-8 text-xs font-bold uppercase tracking-widest text-muted md:flex">
          <a
            href="#agents"
            className="transition-colors hover:text-foreground"
          >
            {t("features")}
          </a>
          <a
            href="#opensource"
            className="transition-colors hover:text-foreground"
          >
            {t("github")}
          </a>
        </nav>
        <div className="flex items-center gap-3">
          <a
            href={GITHUB_REPO_URL}
            target="_blank"
            rel="noopener noreferrer"
            aria-label="GitHub repository"
            className="hidden size-9 items-center justify-center rounded-md text-muted transition-colors hover:bg-surface/60 hover:text-foreground sm:inline-flex"
          >
            <Github className="size-4" />
          </a>
          <LanguageSwitcher />
          <ThemeToggle />
          <Link
            href={`/${locale}/login`}
            className="inline-flex items-center rounded-full bg-foreground px-5 py-2 text-xs font-bold uppercase tracking-wider text-background transition-colors hover:bg-accent hover:text-accent-foreground"
          >
            {t("login")}
          </Link>
        </div>
      </div>
    </header>
  );
}
