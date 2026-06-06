import { createFileRoute, Outlet, redirect, Link, useNavigate } from '@tanstack/react-router'
import { useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Users, FolderKanban, LayoutDashboard, LogOut, Bot, ChevronDown, Settings, User, Workflow, List, Cpu, Wrench, BookOpen, ShieldCheck, KeyRound, Building2, Server } from 'lucide-react'

export const Route = createFileRoute('/_authenticated')({
  beforeLoad: ({ context }) => {
    // Wait for auth to initialize before redirecting
    if (context.auth.isLoading) {
      return
    }
    if (!context.auth.isAuthenticated) {
      throw redirect({ to: '/login' })
    }
  },
  component: AuthenticatedLayout,
})

function AuthenticatedLayout() {
  const { user, logout, isPlatformAdmin, isOrgAdmin } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate({ to: '/login' })
  }

  const adminLabel = isPlatformAdmin && isOrgAdmin
    ? 'Platform & Organization Admin'
    : isPlatformAdmin
      ? 'Platform Admin'
      : isOrgAdmin
        ? 'Organization Admin'
        : 'User'

  // Get user initials for avatar
  const getInitials = (email: string) => {
    const parts = email.split('@')[0].split(/[._-]/)
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase()
    }
    return email.substring(0, 2).toUpperCase()
  }

  return (
    <div className="min-h-screen flex flex-col">
      {/* Top Navigation Bar */}
      <header className="h-14 border-b bg-card flex items-center px-4 gap-4">
        {/* Logo */}
        <Link to="/dashboard" className="flex items-center gap-2 font-bold text-lg">
          <img src="/logo-mark.svg" alt="Myrmec" className="h-7 w-7" />
          <span>Myrmec</span>
        </Link>

        {/* Left side navigation */}
        <nav className="flex items-center gap-1 ml-4">
          <Link to="/dashboard">
            <Button variant="ghost" size="sm">
              <LayoutDashboard className="h-4 w-4 mr-2" />
              Dashboard
            </Button>
          </Link>

          {/* Workflow Management Dropdown - visible to all */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm">
                <Workflow className="h-4 w-4 mr-2" />
                Workflow Management
                <ChevronDown className="h-4 w-4 ml-1" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              <DropdownMenuLabel>Workflows</DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem asChild>
                <Link to="/workflows" className="cursor-pointer">
                  <List className="h-4 w-4 mr-2" />
                  Workflow List
                </Link>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          {/* Platform Management Dropdown - PLATFORM_ADMIN */}
          {isPlatformAdmin && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm">
                  <Server className="h-4 w-4 mr-2" />
                  Platform
                  <ChevronDown className="h-4 w-4 ml-1" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuLabel>Platform</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <Link to="/models" className="cursor-pointer">
                    <Cpu className="h-4 w-4 mr-2" />
                    Models
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/agents" className="cursor-pointer">
                    <Bot className="h-4 w-4 mr-2" />
                    Agents
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/agent-profiles" className="cursor-pointer">
                    <Bot className="h-4 w-4 mr-2" />
                    Agent Profiles
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/tools" className="cursor-pointer">
                    <Wrench className="h-4 w-4 mr-2" />
                    Tools
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/knowledge" className="cursor-pointer">
                    <BookOpen className="h-4 w-4 mr-2" />
                    Knowledge
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/users" className="cursor-pointer">
                    <Users className="h-4 w-4 mr-2" />
                    Users
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/auth-providers" className="cursor-pointer">
                    <ShieldCheck className="h-4 w-4 mr-2" />
                    Authentication Providers
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/admin/secrets" className="cursor-pointer">
                    <KeyRound className="h-4 w-4 mr-2" />
                    Global Secrets
                  </Link>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}

          {/* Organization Management Dropdown - ORG_ADMIN */}
          {isOrgAdmin && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm">
                  <Building2 className="h-4 w-4 mr-2" />
                  Organization
                  <ChevronDown className="h-4 w-4 ml-1" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuLabel>Organization</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <Link to="/admin/groups" className="cursor-pointer">
                    <Building2 className="h-4 w-4 mr-2" />
                    Groups
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <Link to="/projects" className="cursor-pointer">
                    <FolderKanban className="h-4 w-4 mr-2" />
                    Projects
                  </Link>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </nav>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Right side - User menu */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="gap-2">
              <Avatar className="h-7 w-7">
                <AvatarFallback className="text-xs">
                  {user?.email ? getInitials(user.email) : 'U'}
                </AvatarFallback>
              </Avatar>
              <span className="hidden sm:inline-block max-w-[150px] truncate">
                {user?.email}
              </span>
              <ChevronDown className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel>
              <div className="flex flex-col space-y-1">
                <p className="text-sm font-medium">{user?.email}</p>
                <p className="text-xs text-muted-foreground">
                  {adminLabel}
                </p>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem disabled>
              <User className="h-4 w-4 mr-2" />
              Profile
            </DropdownMenuItem>
            <DropdownMenuItem disabled>
              <Settings className="h-4 w-4 mr-2" />
              Settings
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
              <LogOut className="h-4 w-4 mr-2" />
              Sign out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </header>

      {/* Main content */}
      <main className="flex-1 bg-muted/40">
        <Outlet />
      </main>
    </div>
  )
}

