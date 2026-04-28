"use client";

import { useState } from "react";
import type { ComponentType } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useLocale } from "next-intl";
import { Button } from "@heroui/react";
import { ArrowLeft, Bot, Brain, Building2, Code2, Cpu, Gift, /* GitBranch, */ PanelLeft, PanelLeftClose, Settings2, ShieldCheck, Sliders, Wrench } from "lucide-react";
import { LOCALES } from "@/i18n/config";

type MenuItem = {
  key: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  href: string;
  matchPath: string;
};

type MenuSection = {
  title: string;
  items: MenuItem[];
};

export function AdminSidebar() {
  const locale = useLocale();
  const pathname = usePathname();
  const [isCollapsed, setIsCollapsed] = useState(false);

  const getPathWithoutLocale = (path: string) => {
    const segments = path.split("/").filter(Boolean);
    if (segments.length > 0 && LOCALES.includes(segments[0] as typeof LOCALES[number])) {
      return "/" + segments.slice(1).join("/");
    }
    return path;
  };

  const normalizedPathname = getPathWithoutLocale(pathname);

  const sections: MenuSection[] = [
    {
      title: "模型管理",
      items: [
        {
          key: "aiModels",
          label: "AI生成模型",
          icon: Bot,
          href: `/${locale}/admin/models/ai-models`,
          matchPath: "/admin/models/ai-models",
        },
        {
          key: "llmModels",
          label: "LLM模型",
          icon: Brain,
          href: `/${locale}/admin/models/llm-models`,
          matchPath: "/admin/models/llm-models",
        },
        {
          key: "groovy",
          label: "Groovy管理",
          icon: Code2,
          href: `/${locale}/admin/models/groovy`,
          matchPath: "/admin/models/groovy",
        },
      ],
    },
    {
      title: "Agent管理",
      items: [
        {
          key: "agentBilling",
          label: "Agent计费",
          icon: Cpu,
          href: `/${locale}/admin/agents/billing`,
          matchPath: "/admin/agents/billing",
        },
        {
          key: "agentConfigs",
          label: "Agent配置",
          icon: Sliders,
          href: `/${locale}/admin/agents/configs`,
          matchPath: "/admin/agents/configs",
        },
        {
          key: "agentSkills",
          label: "Skill管理",
          icon: Wrench,
          href: `/${locale}/admin/agents/skills`,
          matchPath: "/admin/agents/skills",
        },
        {
          key: "agentTools",
          label: "工具目录",
          icon: ShieldCheck,
          href: `/${locale}/admin/agents/tools`,
          matchPath: "/admin/agents/tools",
        },
        // Pipeline entry temporarily hidden
        // {
        //   key: "pipeline",
        //   label: "Pipeline管理",
        //   icon: GitBranch,
        //   href: `/${locale}/admin/pipeline`,
        //   matchPath: "/admin/pipeline",
        // },
      ],
    },
    {
      title: "系统",
      items: [
        {
          key: "workspaces",
          label: "Workspace管理",
          icon: Building2,
          href: `/${locale}/admin/workspaces`,
          matchPath: "/admin/workspaces",
        },
        {
          key: "systemConfigs",
          label: "系统配置",
          icon: Settings2,
          href: `/${locale}/admin/system/configs`,
          matchPath: "/admin/system/configs",
        },
        {
          key: "giftCodes",
          label: "礼包码管理",
          icon: Gift,
          href: `/${locale}/admin/system/gift-codes`,
          matchPath: "/admin/system/gift-codes",
        },
      ],
    },
  ];

  const isActive = (matchPath: string) =>
    normalizedPathname === matchPath || normalizedPathname.startsWith(`${matchPath}/`);

  return (
    <aside
      className={`flex h-full flex-col transition-all duration-300 ${
        isCollapsed ? "w-14" : "w-52"
      }`}
    >
      {/* Header + Collapse Toggle */}
      <div
        className={`flex items-center border-b border-border px-3 py-2 ${
          isCollapsed ? "justify-center" : "justify-between"
        }`}
      >
        {!isCollapsed && (
          <span className="text-xs font-medium text-muted">系统管理后台</span>
        )}
        <Button
          isIconOnly
          variant="ghost"
          size="sm"
          onPress={() => setIsCollapsed((v) => !v)}
          aria-label={isCollapsed ? "展开侧边栏" : "收起侧边栏"}
          className="size-7"
        >
          {isCollapsed ? (
            <PanelLeft className="size-4" />
          ) : (
            <PanelLeftClose className="size-4" />
          )}
        </Button>
      </div>

      {/* Sidebar Content */}
      <div className="flex-1 overflow-y-auto p-2">
        {sections.map((section, index) => (
          <div key={section.title} className={index > 0 ? "mt-4" : ""}>
            {!isCollapsed && (
              <h3 className="mb-2 px-3 text-xs font-semibold uppercase tracking-wider text-muted">
                {section.title}
              </h3>
            )}
            <nav className="flex flex-col gap-1">
              {section.items.map((item) => {
                const Icon = item.icon;
                const active = isActive(item.matchPath);
                return (
                  <Link
                    key={item.key}
                    href={item.href}
                    title={isCollapsed ? item.label : undefined}
                    className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all ${
                      active
                        ? "bg-accent text-accent-foreground shadow-sm"
                        : "text-muted hover:bg-surface hover:text-foreground"
                    } ${isCollapsed ? "justify-center" : ""}`}
                  >
                    <Icon className="size-5 shrink-0" />
                    {!isCollapsed && <span>{item.label}</span>}
                  </Link>
                );
              })}
            </nav>
          </div>
        ))}
      </div>

      {/* Back to Workspace */}
      <div className="border-t border-border p-2">
        <Link
          href={`/${locale}/workspace/management/members`}
          title={isCollapsed ? "返回工作区" : undefined}
          className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-muted transition-all hover:bg-surface hover:text-foreground ${
            isCollapsed ? "justify-center" : ""
          }`}
        >
          <ArrowLeft className="size-5 shrink-0" />
          {!isCollapsed && <span>返回工作区</span>}
        </Link>
      </div>
    </aside>
  );
}

export default AdminSidebar;
