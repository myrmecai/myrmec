import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeBasesApi,
  type KnowledgeBase,
  type KnowledgeSource,
  type CreateKnowledgeBaseRequest,
  type CreateKnowledgeSourceRequest,
  type SyncResult,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Plus, Trash2, ChevronLeft, Database, RefreshCw, GitBranch } from 'lucide-react'

export const Route = createFileRoute(
  '/_authenticated/projects/$projectId/knowledge-bases',
)({
  component: ProjectKnowledgeBasesPage,
})

function syncStatusVariant(status: string | null): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'SUCCESS':
      return 'default'
    case 'PARTIAL':
      return 'secondary'
    case 'FAILED':
      return 'destructive'
    default:
      return 'outline'
  }
}

function ProjectKnowledgeBasesPage() {
  const { projectId } = Route.useParams()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [selectedKbId, setSelectedKbId] = useState<string | null>(null)

  const { data: bases, isLoading, error } = useQuery({
    queryKey: ['knowledge-bases', projectId],
    queryFn: () => knowledgeBasesApi.list(projectId),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateKnowledgeBaseRequest) => knowledgeBasesApi.create(projectId, data),
    onSuccess: (kb) => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases', projectId] })
      setCreateOpen(false)
      setSelectedKbId(kb.id)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (kbId: string) => knowledgeBasesApi.delete(projectId, kbId),
    onSuccess: (_, kbId) => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases', projectId] })
      if (selectedKbId === kbId) setSelectedKbId(null)
    },
  })

  const selectedKb = bases?.find((b) => b.id === selectedKbId) ?? null

  return (
    <div className="p-8">
      <div className="mb-4">
        <Link
          to="/projects"
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft className="h-4 w-4 mr-1" />
          Back to Projects
        </Link>
      </div>

      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Knowledge Bases</h1>
          <p className="text-muted-foreground">
            Manage retrieval knowledge bases and their connector-backed sources
          </p>
        </div>
        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Knowledge Base
            </Button>
          </DialogTrigger>
          <DialogContent>
            <CreateKnowledgeBaseForm
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Knowledge Bases</CardTitle>
          <CardDescription>{bases?.length ?? 0} knowledge base(s)</CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="text-muted-foreground">Loading knowledge bases...</div>
          ) : error ? (
            <div className="text-destructive">Failed to load knowledge bases</div>
          ) : bases && bases.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Provider</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Sources</TableHead>
                  <TableHead className="w-[120px]">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {bases.map((kb) => (
                  <TableRow
                    key={kb.id}
                    className={
                      kb.id === selectedKbId
                        ? 'cursor-pointer bg-muted/50'
                        : 'cursor-pointer'
                    }
                    onClick={() => setSelectedKbId(kb.id)}
                  >
                    <TableCell className="font-medium">
                      <span className="inline-flex items-center gap-2">
                        <Database className="h-4 w-4 text-muted-foreground" />
                        {kb.name}
                      </span>
                      {kb.description && (
                        <p className="text-xs text-muted-foreground mt-1">{kb.description}</p>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{kb.providerId ?? '—'}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">{kb.status ?? '—'}</Badge>
                    </TableCell>
                    <TableCell>{kb.sourceCount}</TableCell>
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Delete knowledge base"
                        onClick={() => {
                          if (
                            window.confirm(
                              `Delete knowledge base "${kb.name}" and all its sources?`,
                            )
                          ) {
                            deleteMutation.mutate(kb.id)
                          }
                        }}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <div className="text-muted-foreground">
              No knowledge bases yet. Create one to get started.
            </div>
          )}
        </CardContent>
      </Card>

      {selectedKb && (
        <SourcesSection projectId={projectId} kb={selectedKb} />
      )}
    </div>
  )
}

function SourcesSection({ projectId, kb }: { projectId: string; kb: KnowledgeBase }) {
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [syncResults, setSyncResults] = useState<Record<string, SyncResult>>({})

  const { data: sources, isLoading } = useQuery({
    queryKey: ['knowledge-sources', projectId, kb.id],
    queryFn: () => knowledgeBasesApi.listSources(projectId, kb.id),
  })

  const addMutation = useMutation({
    mutationFn: (data: CreateKnowledgeSourceRequest) =>
      knowledgeBasesApi.addSource(projectId, kb.id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-sources', projectId, kb.id] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases', projectId] })
      setAddOpen(false)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (sourceId: string) =>
      knowledgeBasesApi.deleteSource(projectId, kb.id, sourceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-sources', projectId, kb.id] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases', projectId] })
    },
  })

  const syncMutation = useMutation({
    mutationFn: (sourceId: string) => knowledgeBasesApi.sync(projectId, kb.id, sourceId),
    onSuccess: (result, sourceId) => {
      setSyncResults((prev) => ({ ...prev, [sourceId]: result }))
      queryClient.invalidateQueries({ queryKey: ['knowledge-sources', projectId, kb.id] })
    },
  })

  return (
    <Card className="mt-8">
      <CardHeader className="flex flex-row items-center justify-between">
        <div>
          <CardTitle>Sources — {kb.name}</CardTitle>
          <CardDescription>{sources?.length ?? 0} source(s)</CardDescription>
        </div>
        <Dialog open={addOpen} onOpenChange={setAddOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              Add Source
            </Button>
          </DialogTrigger>
          <DialogContent>
            <AddSourceForm
              onSubmit={(data) => addMutation.mutate(data)}
              isLoading={addMutation.isPending}
              error={addMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div className="text-muted-foreground">Loading sources...</div>
        ) : sources && sources.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Connector</TableHead>
                <TableHead>URI</TableHead>
                <TableHead>Last Sync</TableHead>
                <TableHead>Chunks</TableHead>
                <TableHead className="w-[160px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sources.map((source) => (
                <SourceRow
                  key={source.id}
                  source={source}
                  syncResult={syncResults[source.id]}
                  isSyncing={syncMutation.isPending && syncMutation.variables === source.id}
                  onSync={() => syncMutation.mutate(source.id)}
                  onDelete={() => {
                    if (window.confirm(`Delete source "${source.name}"?`)) {
                      deleteMutation.mutate(source.id)
                    }
                  }}
                />
              ))}
            </TableBody>
          </Table>
        ) : (
          <div className="text-muted-foreground">
            No sources yet. Add a connector-backed source to sync content.
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function SourceRow({
  source,
  syncResult,
  isSyncing,
  onSync,
  onDelete,
}: {
  source: KnowledgeSource
  syncResult?: SyncResult
  isSyncing: boolean
  onSync: () => void
  onDelete: () => void
}) {
  return (
    <>
      <TableRow>
        <TableCell className="font-medium">{source.name}</TableCell>
        <TableCell>
          <Badge variant="outline" className="inline-flex items-center gap-1">
            {source.connectorType === 'git' && <GitBranch className="h-3 w-3" />}
            {source.connectorType}
          </Badge>
        </TableCell>
        <TableCell className="max-w-[260px] truncate" title={source.uri}>
          {source.uri}
        </TableCell>
        <TableCell>
          <Badge variant={syncStatusVariant(source.lastSyncStatus)}>
            {source.lastSyncStatus ?? 'Never'}
          </Badge>
        </TableCell>
        <TableCell>{source.chunkCount}</TableCell>
        <TableCell>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              title="Sync now"
              disabled={isSyncing}
              onClick={onSync}
            >
              <RefreshCw className={isSyncing ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
            </Button>
            <Button variant="ghost" size="icon" title="Delete source" onClick={onDelete}>
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </TableCell>
      </TableRow>
      {syncResult && (
        <TableRow>
          <TableCell colSpan={6} className="py-2">
            <div
              className={
                syncResult.status === 'FAILED'
                  ? 'text-sm text-destructive'
                  : 'text-sm text-muted-foreground'
              }
            >
              Last manual sync: {syncResult.status} — {syncResult.chunksEmitted} chunk(s),{' '}
              {syncResult.errorCount} error(s), {syncResult.durationMs}ms
              {syncResult.errors.length > 0 && (
                <ul className="list-disc ml-6 mt-1">
                  {syncResult.errors.slice(0, 5).map((err, i) => (
                    <li key={i}>{err}</li>
                  ))}
                </ul>
              )}
            </div>
          </TableCell>
        </TableRow>
      )}
    </>
  )
}

function CreateKnowledgeBaseForm({
  onSubmit,
  isLoading,
  error,
}: {
  onSubmit: (data: CreateKnowledgeBaseRequest) => void
  isLoading: boolean
  error?: string
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const { data: capabilities } = useQuery({
    queryKey: ['knowledge-capabilities'],
    queryFn: () => knowledgeBasesApi.capabilities(),
  })

  const providerIds = capabilities?.providerIds ?? []
  const [providerId, setProviderId] = useState('')

  const effectiveProvider = providerId || providerIds[0] || ''

  return (
    <>
      <DialogHeader>
        <DialogTitle>New Knowledge Base</DialogTitle>
        <DialogDescription>
          Create a retrieval knowledge base scoped to this project.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="kb-name">Name</Label>
          <Input
            id="kb-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Team documentation"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="kb-description">Description</Label>
          <Textarea
            id="kb-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Optional description"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="kb-provider">Retrieval provider</Label>
          <Select value={effectiveProvider} onValueChange={setProviderId}>
            <SelectTrigger id="kb-provider">
              <SelectValue placeholder="Select provider" />
            </SelectTrigger>
            <SelectContent>
              {providerIds.map((id) => (
                <SelectItem key={id} value={id}>
                  {id}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
      <DialogFooter>
        <Button
          disabled={!name.trim() || isLoading}
          onClick={() =>
            onSubmit({
              name: name.trim(),
              description: description.trim() || null,
              providerId: effectiveProvider || null,
            })
          }
        >
          {isLoading ? 'Creating...' : 'Create'}
        </Button>
      </DialogFooter>
    </>
  )
}

function AddSourceForm({
  onSubmit,
  isLoading,
  error,
}: {
  onSubmit: (data: CreateKnowledgeSourceRequest) => void
  isLoading: boolean
  error?: string
}) {
  const { data: capabilities } = useQuery({
    queryKey: ['knowledge-capabilities'],
    queryFn: () => knowledgeBasesApi.capabilities(),
  })

  const connectorTypes = capabilities?.connectorTypes ?? []
  const [connectorType, setConnectorType] = useState('')
  const [name, setName] = useState('')
  const [uri, setUri] = useState('')
  const [configJson, setConfigJson] = useState('')
  const [syncSchedule, setSyncSchedule] = useState('')

  const effectiveConnector = connectorType || connectorTypes[0] || ''

  return (
    <>
      <DialogHeader>
        <DialogTitle>Add Source</DialogTitle>
        <DialogDescription>
          Attach a connector-backed source that can be synced into this knowledge base.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="src-connector">Connector</Label>
          <Select value={effectiveConnector} onValueChange={setConnectorType}>
            <SelectTrigger id="src-connector">
              <SelectValue placeholder="Select connector" />
            </SelectTrigger>
            <SelectContent>
              {connectorTypes.map((type) => (
                <SelectItem key={type} value={type}>
                  {type}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="src-name">Name</Label>
          <Input
            id="src-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="docs-repo"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="src-uri">URI</Label>
          <Input
            id="src-uri"
            value={uri}
            onChange={(e) => setUri(e.target.value)}
            placeholder="https://github.com/org/repo.git"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="src-config">Config (JSON, optional)</Label>
          <Textarea
            id="src-config"
            value={configJson}
            onChange={(e) => setConfigJson(e.target.value)}
            placeholder='{"branch":"main","includeGlobs":["docs/**"]}'
            className="font-mono text-xs"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="src-schedule">Sync schedule (6-field cron, optional)</Label>
          <Input
            id="src-schedule"
            value={syncSchedule}
            onChange={(e) => setSyncSchedule(e.target.value)}
            placeholder="0 0 2 * * * (daily 02:00; blank = manual only)"
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>
      <DialogFooter>
        <Button
          disabled={!effectiveConnector || !name.trim() || !uri.trim() || isLoading}
          onClick={() =>
            onSubmit({
              connectorType: effectiveConnector,
              name: name.trim(),
              uri: uri.trim(),
              configJson: configJson.trim() || null,
              syncSchedule: syncSchedule.trim() || null,
            })
          }
        >
          {isLoading ? 'Adding...' : 'Add Source'}
        </Button>
      </DialogFooter>
    </>
  )
}
