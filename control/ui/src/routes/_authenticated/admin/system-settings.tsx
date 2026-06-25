import { createFileRoute } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  systemSettingsApi,
  type SystemSetting,
  type SettingType,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Settings, Pencil } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/admin/system-settings')({
  component: SystemSettingsPage,
})

const TYPE_HINT: Record<SettingType, string> = {
  STRING: 'Free text.',
  INT: 'Whole number.',
  RATIO: 'Decimal between 0 and 1.',
  BOOL: "'true' or 'false'.",
  MODEL_REF: 'Model code. Leave blank to use the session model.',
  JSON: 'Valid JSON document.',
}

function SystemSettingsPage() {
  const queryClient = useQueryClient()
  const [edit, setEdit] = useState<SystemSetting | null>(null)
  const [draft, setDraft] = useState('')

  const { data: settings, isLoading, error } = useQuery({
    queryKey: ['system-settings'],
    queryFn: () => systemSettingsApi.list(),
  })

  useEffect(() => {
    setDraft(edit?.value ?? '')
  }, [edit])

  const updateMutation = useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) =>
      systemSettingsApi.update(key, { value }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-settings'] })
      setEdit(null)
    },
  })

  const save = () => {
    if (!edit) return
    updateMutation.mutate({ key: edit.key, value: draft })
  }

  return (
    <div className="container mx-auto py-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Settings className="h-5 w-5" />
            System Settings
          </CardTitle>
          <CardDescription>
            Platform-level typed configuration. A blank value resets a setting
            to its built-in default. Every change is recorded in the audit log.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
          {error && (
            <p className="text-destructive text-sm" data-testid="system-settings-error">
              Failed to load system settings.
            </p>
          )}
          {settings && (
            <Table data-testid="system-settings-table">
              <TableHeader>
                <TableRow>
                  <TableHead>Key</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Value</TableHead>
                  <TableHead>Description</TableHead>
                  <TableHead className="w-16" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {settings.map((s) => (
                  <TableRow key={s.key} data-testid={`setting-row-${s.key}`}>
                    <TableCell className="font-mono text-xs">{s.key}</TableCell>
                    <TableCell>
                      <Badge variant="secondary">{s.valueType}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {s.value && s.value.length > 0 ? (
                        s.value
                      ) : (
                        <span className="text-muted-foreground italic">default</span>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground max-w-md text-xs">
                      {s.description}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Edit ${s.key}`}
                        data-testid={`setting-edit-${s.key}`}
                        onClick={() => setEdit(s)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={edit !== null} onOpenChange={(open) => !open && setEdit(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="font-mono text-sm">{edit?.key}</DialogTitle>
            <DialogDescription>
              {edit ? TYPE_HINT[edit.valueType] : ''}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="setting-value">Value</Label>
            <Input
              id="setting-value"
              data-testid="setting-value-input"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  save()
                }
              }}
              placeholder="Leave blank to reset to default"
            />
            {edit?.description && (
              <p className="text-muted-foreground text-xs">{edit.description}</p>
            )}
            {updateMutation.isError && (
              <p className="text-destructive text-xs" data-testid="setting-save-error">
                Could not save — check the value matches the type.
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEdit(null)}>
              Cancel
            </Button>
            <Button
              data-testid="setting-save"
              onClick={save}
              disabled={updateMutation.isPending}
            >
              {updateMutation.isPending ? 'Saving…' : 'Save'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
