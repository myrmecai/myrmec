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
import { LayoutDashboard, LogOut, Settings, User, Inbox, ChevronDown } from 'lucide-react'
import { QuotaBanner } from '@/components/quota-banner'
import { Sidebar } from '@/components/sidebar'

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
      {/* Slim Top Bar — logo, Dashboard + My Work shortcuts, user menu */}
      <header className="h-14 border-b bg-card flex items-center px-4 gap-4 shrink-0">
        <Link to="/dashboard" className="flex items-center gap-2 font-bold text-lg">
          <img src="/logo-mark.svg" alt="Myrmec" className="h-7 w-7" />
          <span>Myrmec</span>
        </Link>

        <nav className="flex items-center gap-1 ml-4">
          <Link to="/dashboard">
            <Button variant="ghost" size="sm">
              <LayoutDashboard className="h-4 w-4 mr-2" />
              Dashboard
            </Button>
          </Link>
          <Link to="/my-work">
            <Button variant="ghost" size="sm">
              <Inbox className="h-4 w-4 mr-2" />
              My Work
            </Button>
          </Link>
        </nav>

        <div className="flex-1" />

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

      {/* Sidebar + Content */}
      <div className="flex flex-1 min-h-0">
        <Sidebar />
        <main className="flex-1 bg-muted/40 overflow-auto">
          <QuotaBanner />
          <Outlet />
        </main>
      </div>
    </div>
  )
}

