// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState, useMemo } from 'react'
import { Link, useMatch } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/lib/auth'
import {
  LayoutDashboard,
  Inbox,
  Workflow,
  MessageSquare,
  Server,
  Building2,
  FolderKanban,
  Users,
  Layers,
  Cpu,
  ShieldCheck,
  Link2,
  BrainCircuit,
  Wallet,
  ChevronRight,
  ChevronDown,
  PanelLeftClose,
  PanelLeftOpen,
  type LucideIcon,
} from 'lucide-react'

interface NavItem {
  label: string
  to: string
  icon: LucideIcon
}

interface NavSection {
  title: string
  icon: LucideIcon
  items: NavItem[]
  defaultOpen?: boolean
}

export function Sidebar() {
  const { isPlatformAdmin, isOrgAdmin, hasSystemRole } = useAuth()

  // Auto-expand the section containing the active route.
  const dashboardMatch = useMatch({ from: '/_authenticated/dashboard', shouldThrow: false })
  const myWorkMatch = useMatch({ from: '/_authenticated/my-work', shouldThrow: false })
  const workflowsMatch = useMatch({ from: '/_authenticated/workflows/', shouldThrow: false })
  const assistantsMatch = useMatch({ from: '/_authenticated/assistants/', shouldThrow: false })
  const budgetsMatch = useMatch({ from: '/_authenticated/budgets', shouldThrow: false })

  // Platform section is active when on /platform or any nested platform route.
  const platformActive = !!platformMatch || checkActive('/platform')
  const budgetsActive = !!budgetsMatch || checkActive('/budgets')

  const [openSections, setOpenSections] = useState<Record<string, boolean>>({
    services: servicesActive,
    budgets: budgetsActive,
    platform: platformActive,
    organization: orgActive,
  })

  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem('myrmec.sidebar.collapsed') === 'true'
    } catch {
      return false
    }
  })

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev
      try { localStorage.setItem('myrmec.sidebar.collapsed', String(next)) } catch {}
      return next
    })
  }

  const toggleSection = (key: string) =>
    setOpenSections((prev) => ({ ...prev, [key]: !prev[key] }))

  const sections: NavSection[] = useMemo(() => {
    const result: NavSection[] = [
      {
        title: 'Services',
        icon: Layers,
        defaultOpen: true,
        items: [
          { label: 'Workflows', to: '/workflows', icon: Workflow },
          { label: 'Assistants', to: '/assistants', icon: MessageSquare },
        ],
      },
    ]

    if (isPlatformAdmin || hasSystemRole('BUDGET_OWNER') || isOrgAdmin) {
      result.push({
        title: 'Budgets',
        icon: Wallet,
        defaultOpen: budgetsActive,
        items: [
          { label: 'Budget Overview', to: '/budgets', icon: Wallet },
        ],
      })
    }

    if (isPlatformAdmin) {
      result.push({
        title: 'Platform',
        icon: Server,
        items: [
          { label: 'AI Infrastructure', to: '/platform/ai-infra/models', icon: Cpu },
          { label: 'AI Context', to: '/platform/ai-context/governance-profile', icon: BrainCircuit },
          { label: 'Connections', to: '/platform/connections', icon: Link2 },
          { label: 'Security & Access', to: '/platform/security/secrets', icon: ShieldCheck },
        ],
      })
    }

    if (isOrgAdmin) {
      result.push({
        title: 'Organization',
        icon: Building2,
        items: [
          { label: 'Groups', to: '/admin/groups', icon: Building2 },
          { label: 'Projects', to: '/projects', icon: FolderKanban },
          { label: 'Users', to: '/users', icon: Users },
        ],
      })
    }

    return result
  }, [isPlatformAdmin, isOrgAdmin])

  // Collapsed mode: icon-only rail with tooltips.
  if (collapsed) {
    return (
      <aside className="w-12 border-r bg-card flex flex-col shrink-0 items-center py-2 gap-1 overflow-y-auto">
        <button
          onClick={toggleCollapsed}
          className="flex items-center justify-center w-8 h-8 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
          title="Expand sidebar"
        >
          <PanelLeftOpen className="h-4 w-4" />
        </button>
        <div className="h-px w-8 bg-border my-1" />
        <IconLink to="/dashboard" icon={LayoutDashboard} label="Dashboard" active={!!dashboardMatch} />
        <IconLink to="/my-work" icon={Inbox} label="My Work" active={!!myWorkMatch} />
        <div className="h-px w-8 bg-border my-1" />
        {sections.map((section) =>
          section.items.map((item) => (
            <IconLink
              key={item.to}
              to={item.to}
              icon={item.icon}
              label={item.label}
              active={checkActive(item.to)}
            />
          )),
        )}
      </aside>
    )
  }

  // Expanded mode: full sidebar with labels and collapsible sections.
  return (
    <aside className="w-60 border-r bg-card flex flex-col shrink-0 overflow-y-auto">
      {/* Collapse toggle */}
      <div className="p-2 flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground px-2">Navigation</span>
        <button
          onClick={toggleCollapsed}
          className="flex items-center justify-center w-7 h-7 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
          title="Collapse sidebar"
        >
          <PanelLeftClose className="h-4 w-4" />
        </button>
      </div>

      {/* Quick links */}
      <div className="px-2 space-y-1">
        <SidebarLink to="/dashboard" icon={LayoutDashboard} label="Dashboard" active={!!dashboardMatch} />
        <SidebarLink to="/my-work" icon={Inbox} label="My Work" active={!!myWorkMatch} />
      </div>

      <div className="h-px bg-border mx-2" />

      {/* Collapsible sections */}
      <nav className="flex-1 p-2 space-y-1">
        {sections.map((section) => {
          const key = section.title.toLowerCase()
          const isOpen = openSections[key] ?? section.defaultOpen ?? false
          const SectionIcon = section.icon
          return (
            <div key={key}>
              <button
                onClick={() => toggleSection(key)}
                className="flex items-center w-full px-2 py-1.5 text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-accent rounded-md transition-colors"
              >
                <SectionIcon className="h-4 w-4 mr-2 shrink-0" />
                <span className="flex-1 text-left">{section.title}</span>
                {isOpen ? (
                  <ChevronDown className="h-4 w-4 shrink-0" />
                ) : (
                  <ChevronRight className="h-4 w-4 shrink-0" />
                )}
              </button>
              {isOpen && (
                <div className="ml-2 mt-0.5 space-y-0.5 border-l border-border pl-2">
                  {section.items.map((item) => {
                    const active = checkActive(item.to)
                    return (
                      <SidebarLink
                        key={item.to}
                        to={item.to}
                        icon={item.icon}
                        label={item.label}
                        active={active}
                        compact
                      />
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </nav>
    </aside>
  )
}

/** Check if a route path is active based on the current URL. */
function checkActive(to: string): boolean {
  if (typeof window === 'undefined') return false
  const current = window.location.pathname.replace(/\/$/, '')
  const target = to
  return current === target || current.startsWith(target + '/')
}

function SidebarLink({
  to,
  icon: Icon,
  label,
  active,
  compact,
}: {
  to: string
  icon: LucideIcon
  label: string
  active: boolean
  compact?: boolean
}) {
  return (
    <Link to={to}>
      <Button
        variant={active ? 'secondary' : 'ghost'}
        size="sm"
        className={`w-full justify-start ${compact ? 'h-8 text-xs' : ''} ${active ? 'font-medium' : ''}`}
      >
        <Icon className="h-4 w-4 mr-2 shrink-0" />
        {label}
      </Button>
    </Link>
  )
}

/** Icon-only link for the collapsed sidebar rail. Uses native title tooltip. */
function IconLink({
  to,
  icon: Icon,
  label,
  active,
}: {
  to: string
  icon: LucideIcon
  label: string
  active: boolean
}) {
  return (
    <Link to={to} title={label}>
      <Button
        variant={active ? 'secondary' : 'ghost'}
        size="icon"
        className={`h-8 w-8 shrink-0 ${active ? 'font-medium' : ''}`}
      >
        <Icon className="h-4 w-4" />
      </Button>
    </Link>
  )
}