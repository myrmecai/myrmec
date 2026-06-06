import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeApi,
  projectKnowledgeReposApi,
  projectSecretsApi,
  globalSecretsApi,
  type KnowledgeDocument,
  type CreateKnowledgeDocumentRequest,
  type UpdateKnowledgeDocumentRequest,
  type KnowledgeCategory,
  type ProjectKnowledgeRepo,
  type ProjectKnowledgeRepoRequest,
  type Secret,
} from '@/lib/api'
import { isGitCompatible } from '@/lib/secrets-form'
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
import {
  Plus,
  Pencil,
  Trash2,
  Check,
  X,
  BookOpen,
  FileText,
  ScrollText,
  Building2,
  ChevronLeft,
  GitBranch,
} from 'lucide-react'

export const Route = createFileRoute(
  '/_authenticated/projects/$projectId/knowledge',
)({
  component: ProjectKnowledgePage,
})

const CATEGORY_CONFIG: Record<KnowledgeCategory, { label: string; icon: typeof BookOpen; color: string }> = {
  STANDARD: { label: 'Standard', icon: ScrollText, color: 'bg-blue-500' },
  INSTRUCTION: { label: 'Instruction', icon: BookOpen, color: 'bg-green-500' },
  REQUIREMENT: { label: 'Requirement', icon: FileText, color: 'bg-orange-500' },
  ARCHITECTURE: { label: 'Architecture', icon: Building2, color: 'bg-purple-500' },
}

function ProjectKnowledgePage() {
  const { projectId } = Route.useParams()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editDoc, setEditDoc] = useState<KnowledgeDocument | null>(null)
  const [viewDoc, setViewDoc] = useState<KnowledgeDocument | null>(null)

  const { data: documents, isLoading, error } = useQuery({
    queryKey: ['knowledge', 'project', projectId],
    queryFn: () => knowledgeApi.listProject(projectId),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateKnowledgeDocumentRequest) =>
      knowledgeApi.createProject(projectId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'project', projectId] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateKnowledgeDocumentRequest }) =>
      knowledgeApi.updateProject(projectId, id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'project', projectId] })
      setEditDoc(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => knowledgeApi.deleteProject(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'project', projectId] })
    },
  })

  const activateMutation = useMutation({
    mutationFn: (id: string) => knowledgeApi.activateProject(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'project', projectId] })
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: (id: string) => knowledgeApi.deactivateProject(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'project', projectId] })
    },
  })

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading knowledge documents...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load knowledge documents</div>
      </div>
    )
  }

  return (
    <div className="p-8">
      {/* Breadcrumb */}
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
          <h1 className="text-3xl font-bold">Project Knowledge</h1>
          <p className="text-muted-foreground">
            Manage project-specific standards, instructions, and requirements for agents
          </p>
        </div>
      </div>

      {/* Category Stats */}
      <div className="grid grid-cols-4 gap-4 mb-8">
        {(Object.keys(CATEGORY_CONFIG) as KnowledgeCategory[]).map((category) => {
          const config = CATEGORY_CONFIG[category]
          const count = documents?.filter((d) => d.category === category).length || 0
          const Icon = config.icon
          return (
            <Card key={category}>
              <CardHeader className="flex flex-row items-center justify-between pb-2">
                <CardTitle className="text-sm font-medium">{config.label}s</CardTitle>
                <Icon className="h-4 w-4 text-muted-foreground" />
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold">{count}</div>
                <p className="text-xs text-muted-foreground">
                  {documents?.filter((d) => d.category === category && d.active).length || 0} active
                </p>
              </CardContent>
            </Card>
          )
        })}
      </div>

      {/* Knowledge Sources */}
      <KnowledgeSourcesSection projectId={projectId} />

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>All Documents</CardTitle>
            <CardDescription>
              {documents?.length || 0} knowledge documents configured for this project
            </CardDescription>
          </div>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger asChild>
              <Button variant="outline">
                <Plus className="h-4 w-4 mr-2" />
                New Document
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
              <KnowledgeDocumentForm
                onSubmit={(data) => createMutation.mutate(data)}
                isLoading={createMutation.isPending}
                error={createMutation.error?.message}
              />
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Category</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>Applies To</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Updated</TableHead>
                <TableHead className="w-[120px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {documents?.map((doc) => {
                const config = CATEGORY_CONFIG[doc.category]
                const Icon = config.icon
                return (
                  <TableRow key={doc.id}>
                    <TableCell>
                      <button
                        className="font-medium hover:underline text-left"
                        onClick={() => setViewDoc(doc)}
                      >
                        {doc.name}
                      </button>
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary" className="gap-1">
                        <Icon className="h-3 w-3" />
                        {config.label}
                      </Badge>
                    </TableCell>
                    <TableCell>{doc.priority}</TableCell>
                    <TableCell>
                      {doc.appliesTo && doc.appliesTo.length > 0 ? (
                        <div className="text-xs">
                          {doc.appliesTo.slice(0, 2).join(', ')}
                          {doc.appliesTo.length > 2 && ` +${doc.appliesTo.length - 2}`}
                        </div>
                      ) : (
                        <span className="text-muted-foreground">All</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {doc.active ? (
                        <Badge variant="default" className="bg-green-500">
                          <Check className="h-3 w-3 mr-1" />
                          Active
                        </Badge>
                      ) : (
                        <Badge variant="secondary">
                          <X className="h-3 w-3 mr-1" />
                          Inactive
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      {doc.updatedAt
                        ? new Date(doc.updatedAt).toLocaleDateString()
                        : new Date(doc.createdAt).toLocaleDateString()}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => setEditDoc(doc)}
                          title="Edit"
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        {doc.active ? (
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => deactivateMutation.mutate(doc.id)}
                            title="Deactivate"
                          >
                            <X className="h-4 w-4" />
                          </Button>
                        ) : (
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => activateMutation.mutate(doc.id)}
                            title="Activate"
                          >
                            <Check className="h-4 w-4" />
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => {
                            if (confirm('Delete this knowledge document?')) {
                              deleteMutation.mutate(doc.id)
                            }
                          }}
                          title="Delete"
                        >
                          <Trash2 className="h-4 w-4 text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })}
              {documents?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-muted-foreground py-8">
                    No knowledge documents yet. Create one to get started.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Edit Dialog */}
      <Dialog open={!!editDoc} onOpenChange={(open) => !open && setEditDoc(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editDoc && (
            <KnowledgeDocumentForm
              document={editDoc}
              onSubmit={(data) => updateMutation.mutate({ id: editDoc.id, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* View Dialog */}
      <Dialog open={!!viewDoc} onOpenChange={(open) => !open && setViewDoc(null)}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          {viewDoc && <KnowledgeDocumentView document={viewDoc} />}
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface KnowledgeDocumentFormProps {
  document?: KnowledgeDocument
  onSubmit: (data: CreateKnowledgeDocumentRequest) => void
  isLoading: boolean
  error?: string
}

function KnowledgeDocumentForm({ document, onSubmit, isLoading, error }: KnowledgeDocumentFormProps) {
  const [name, setName] = useState(document?.name || '')
  const [category, setCategory] = useState<KnowledgeCategory>(document?.category || 'INSTRUCTION')
  const [content, setContent] = useState(document?.content || '')
  const [priority, setPriority] = useState(document?.priority?.toString() || '100')
  const [appliesTo, setAppliesTo] = useState(document?.appliesTo?.join(', ') || '')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      category,
      content,
      priority: parseInt(priority, 10),
      appliesTo: appliesTo ? appliesTo.split(',').map((s) => s.trim()).filter(Boolean) : undefined,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>{document ? 'Edit Document' : 'Create Knowledge Document'}</DialogTitle>
        <DialogDescription>
          {document
            ? 'Update the knowledge document details.'
            : 'Add a new project-specific knowledge document for agents.'}
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name *</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g., Java Spring Standards"
              required
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="category">Category *</Label>
            <Select value={category} onValueChange={(v) => setCategory(v as KnowledgeCategory)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(Object.keys(CATEGORY_CONFIG) as KnowledgeCategory[]).map((cat) => (
                  <SelectItem key={cat} value={cat}>
                    {CATEGORY_CONFIG[cat].label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="content">Content *</Label>
          <Textarea
            id="content"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="Enter the knowledge document content (Markdown supported)..."
            className="min-h-[200px] font-mono text-sm"
            required
          />
          <p className="text-xs text-muted-foreground">
            Supports Markdown formatting. This content will be injected into agent system prompts.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="priority">Priority</Label>
            <Input
              id="priority"
              type="number"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
              min="0"
              max="1000"
            />
            <p className="text-xs text-muted-foreground">
              Higher priority documents are included first (0-1000).
            </p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="appliesTo">Applies To (patterns)</Label>
            <Input
              id="appliesTo"
              value={appliesTo}
              onChange={(e) => setAppliesTo(e.target.value)}
              placeholder="e.g., **/*.java, src/main/**"
            />
            <p className="text-xs text-muted-foreground">
              Comma-separated glob patterns. Leave empty for all files.
            </p>
          </div>
        </div>

        {error && <div className="text-sm text-destructive">{error}</div>}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : document ? 'Update Document' : 'Create Document'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface KnowledgeDocumentViewProps {
  document: KnowledgeDocument
}

function KnowledgeDocumentView({ document }: KnowledgeDocumentViewProps) {
  const config = CATEGORY_CONFIG[document.category]
  const Icon = config.icon

  return (
    <>
      <DialogHeader>
        <div className="flex items-center gap-2">
          <Badge variant="secondary" className="gap-1">
            <Icon className="h-3 w-3" />
            {config.label}
          </Badge>
          {document.active ? (
            <Badge variant="default" className="bg-green-500">Active</Badge>
          ) : (
            <Badge variant="secondary">Inactive</Badge>
          )}
        </div>
        <DialogTitle className="mt-2">{document.name}</DialogTitle>
        <DialogDescription>
          Priority: {document.priority} | Created: {new Date(document.createdAt).toLocaleDateString()}
        </DialogDescription>
      </DialogHeader>

      <div className="py-4">
        {document.appliesTo && document.appliesTo.length > 0 && (
          <div className="mb-4">
            <Label>Applies To</Label>
            <div className="flex flex-wrap gap-1 mt-1">
              {document.appliesTo.map((pattern) => (
                <Badge key={pattern} variant="outline" className="font-mono text-xs">
                  {pattern}
                </Badge>
              ))}
            </div>
          </div>
        )}

        <div className="space-y-2">
          <Label>Content</Label>
          <div className="bg-muted rounded-md p-4 font-mono text-sm whitespace-pre-wrap max-h-[400px] overflow-y-auto">
            {document.content}
          </div>
        </div>
      </div>
    </>
  )
}

interface KnowledgeSourcesSectionProps {
  projectId: string
}

function KnowledgeSourcesSection({ projectId }: KnowledgeSourcesSectionProps) {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editRepo, setEditRepo] = useState<ProjectKnowledgeRepo | null>(null)

  const { data: repos } = useQuery({
    queryKey: ['knowledge-repos', projectId],
    queryFn: () => projectKnowledgeReposApi.list(projectId),
  })

  const { data: projectSecrets } = useQuery({
    queryKey: ['project-secrets', projectId],
    queryFn: () => projectSecretsApi.list(projectId),
  })

  const { data: globalSecretsResult } = useQuery({
    queryKey: ['global-secrets'],
    queryFn: async () => {
      try {
        return await globalSecretsApi.list()
      } catch {
        // Non-admins cannot list /admin/secrets; gracefully fall back to project-only.
        return [] as Secret[]
      }
    },
  })

  const gitSecrets: Secret[] = [
    ...(projectSecrets || []),
    ...(globalSecretsResult || []),
  ].filter((s) => isGitCompatible(s.type))

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['knowledge-repos', projectId] })
  }

  const createMutation = useMutation({
    mutationFn: (data: ProjectKnowledgeRepoRequest) =>
      projectKnowledgeReposApi.create(projectId, data),
    onSuccess: () => {
      invalidate()
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: ProjectKnowledgeRepoRequest }) =>
      projectKnowledgeReposApi.update(projectId, id, data),
    onSuccess: () => {
      invalidate()
      setEditRepo(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => projectKnowledgeReposApi.delete(projectId, id),
    onSuccess: invalidate,
  })

  return (
    <Card className="mb-6">
      <CardHeader className="flex flex-row items-center justify-between">
        <div>
          <CardTitle className="flex items-center gap-2">
            <GitBranch className="h-5 w-5" />
            Knowledge Sources
          </CardTitle>
          <CardDescription>
            Git repositories fetched on demand to provide instruction documents to agents
          </CardDescription>
        </div>
        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button variant="outline">
              <Plus className="h-4 w-4 mr-2" />
              Add Source
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-xl">
            <KnowledgeSourceForm
              gitSecrets={gitSecrets}
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Repository</TableHead>
              <TableHead>Branch</TableHead>
              <TableHead>Instruction Paths</TableHead>
              <TableHead>Credential</TableHead>
              <TableHead className="w-[100px]">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {repos?.map((repo) => (
              <TableRow key={repo.id}>
                <TableCell className="font-medium">{repo.name}</TableCell>
                <TableCell className="font-mono text-xs">{repo.repoUrl}</TableCell>
                <TableCell>{repo.branch}</TableCell>
                <TableCell className="text-xs">
                  {repo.instructionPaths && repo.instructionPaths.length > 0
                    ? repo.instructionPaths.join(', ')
                    : <span className="text-muted-foreground">All</span>}
                </TableCell>
                <TableCell className="text-xs">
                  {(() => {
                    if (!repo.credentialSecretId) {
                      return <span className="text-muted-foreground">None</span>
                    }
                    const match = gitSecrets.find((s) => s.id === repo.credentialSecretId)
                    if (!match) {
                      return <span className="font-mono text-xs" title={repo.credentialSecretId}>(unknown)</span>
                    }
                    return (
                      <span>
                        {match.name}
                        <span className="text-muted-foreground ml-1">[{match.scope}]</span>
                      </span>
                    )
                  })()}
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="icon" onClick={() => setEditRepo(repo)} title="Edit">
                      <Pencil className="h-4 w-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => {
                        if (confirm(`Delete source "${repo.name}"?`)) {
                          deleteMutation.mutate(repo.id)
                        }
                      }}
                      title="Delete"
                    >
                      <Trash2 className="h-4 w-4 text-destructive" />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {(!repos || repos.length === 0) && (
              <TableRow>
                <TableCell colSpan={6} className="text-center text-muted-foreground py-6">
                  No knowledge sources configured.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>

      <Dialog open={!!editRepo} onOpenChange={(open) => !open && setEditRepo(null)}>
        <DialogContent className="max-w-xl">
          {editRepo && (
            <KnowledgeSourceForm
              source={editRepo}
              gitSecrets={gitSecrets}
              onSubmit={(data) => updateMutation.mutate({ id: editRepo.id, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </Card>
  )
}

interface KnowledgeSourceFormProps {
  source?: ProjectKnowledgeRepo
  gitSecrets: Secret[]
  onSubmit: (data: ProjectKnowledgeRepoRequest) => void
  isLoading: boolean
  error?: string
}

function KnowledgeSourceForm({ source, gitSecrets, onSubmit, isLoading, error }: KnowledgeSourceFormProps) {
  const [name, setName] = useState(source?.name || '')
  const [repoUrl, setRepoUrl] = useState(source?.repoUrl || '')
  const [branch, setBranch] = useState(source?.branch || 'main')
  const [instructionPaths, setInstructionPaths] = useState(
    source?.instructionPaths?.join(', ') || '',
  )
  const [credentialSecretId, setCredentialSecretId] = useState<string>(
    source?.credentialSecretId || '',
  )

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      repoUrl,
      branch: branch || undefined,
      instructionPaths: instructionPaths
        ? instructionPaths.split(',').map((s) => s.trim()).filter(Boolean)
        : undefined,
      credentialSecretId: credentialSecretId || null,
    })
  }

  const projectScoped = gitSecrets.filter((s) => s.scope === 'PROJECT')
  const globalScoped = gitSecrets.filter((s) => s.scope === 'GLOBAL')

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>{source ? 'Edit Knowledge Source' : 'Add Knowledge Source'}</DialogTitle>
        <DialogDescription>
          A git repository fetched on demand to supply instruction documents.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="source-name">Name *</Label>
          <Input
            id="source-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., team-standards"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="source-repo-url">Repository URL *</Label>
          <Input
            id="source-repo-url"
            value={repoUrl}
            onChange={(e) => setRepoUrl(e.target.value)}
            placeholder="https://github.com/org/repo.git"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="source-branch">Branch</Label>
          <Input
            id="source-branch"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            placeholder="main"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="source-paths">Instruction Paths</Label>
          <Input
            id="source-paths"
            value={instructionPaths}
            onChange={(e) => setInstructionPaths(e.target.value)}
            placeholder="docs/**, .github/instructions/**"
          />
          <p className="text-xs text-muted-foreground">
            Comma-separated glob patterns. Leave empty to scan the whole repository.
          </p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="source-credential">Credential Secret</Label>
          <Select
            value={credentialSecretId || '__none__'}
            onValueChange={(v) => setCredentialSecretId(v === '__none__' ? '' : v)}
          >
            <SelectTrigger id="source-credential">
              <SelectValue placeholder="None (public repository)" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__none__">None (public repository)</SelectItem>
              {projectScoped.length > 0 && (
                <div className="px-2 py-1 text-xs font-medium text-muted-foreground">Project</div>
              )}
              {projectScoped.map((secret) => (
                <SelectItem key={secret.id} value={secret.id}>
                  {secret.name} <span className="text-muted-foreground ml-1">({secret.type})</span>
                </SelectItem>
              ))}
              {globalScoped.length > 0 && (
                <div className="px-2 py-1 text-xs font-medium text-muted-foreground">Global</div>
              )}
              {globalScoped.map((secret) => (
                <SelectItem key={secret.id} value={secret.id}>
                  {secret.name} <span className="text-muted-foreground ml-1">({secret.type})</span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            Only credentials usable for git clones (bearer token, username/password, SSL key) are shown.
          </p>
        </div>
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : source ? 'Update Source' : 'Add Source'}
        </Button>
      </DialogFooter>
    </form>
  )
}
