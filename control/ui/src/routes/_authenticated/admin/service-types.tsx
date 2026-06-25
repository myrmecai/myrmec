import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { serviceTypesApi } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Layers } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/admin/service-types')({
  component: ServiceTypesPage,
})

function ServiceTypesPage() {
  const { data: serviceTypes, isLoading, error } = useQuery({
    queryKey: ['service-types'],
    queryFn: () => serviceTypesApi.list(),
  })

  return (
    <div className="container mx-auto py-6 space-y-6">
      <div className="flex items-center gap-2">
        <Layers className="h-6 w-6 text-muted-foreground" />
        <div>
          <h1 className="text-2xl font-semibold">Service Types</h1>
          <p className="text-sm text-muted-foreground">
            The kinds of automated work the platform can host. Each project chooses which of these
            it allows.
          </p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Platform service types</CardTitle>
          <CardDescription>
            Read-only catalogue. Per-type enable/disable and group defaults arrive in later releases.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading && (
            <p className="text-sm text-muted-foreground">Loading service types…</p>
          )}
          {error && (
            <p className="text-sm text-destructive">
              Failed to load service types.
            </p>
          )}
          {serviceTypes && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Type</TableHead>
                  <TableHead>Description</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Projects enabled</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {serviceTypes.map((type) => (
                  <TableRow key={type.code} data-testid={`service-type-${type.code}`}>
                    <TableCell>
                      <div className="font-medium">{type.displayName}</div>
                      <code className="text-xs text-muted-foreground">{type.code}</code>
                    </TableCell>
                    <TableCell className="max-w-md text-sm text-muted-foreground">
                      {type.description}
                    </TableCell>
                    <TableCell>
                      <Badge variant={type.enabled ? 'default' : 'secondary'}>
                        {type.enabled ? 'Enabled' : 'Disabled'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {type.projectEnabledCount}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
