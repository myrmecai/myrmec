import { createFileRoute } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeApi,
  type KnowledgeDocument,
  type CreateKnowledgeDocumentRequest,
  type UpdateKnowledgeDocumentRequest,
  type KnowledgeCategory,
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
import { Plus, Pencil, Trash2, Check, X, BookOpen, FileText, ScrollText, Building2 } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/knowledge')({
  component: KnowledgePage,
})

const CATEGORY_CONFIG: Record<KnowledgeCategory, { label: string; icon: typeof BookOpen; color: string }> = {
  STANDARD: { label: 'Standard', icon: ScrollText, color: 'bg-blue-500' },
  INSTRUCTION: { label: 'Instruction', icon: BookOpen, color: 'bg-green-500' },
  REQUIREMENT: { label: 'Requirement', icon: FileText, color: 'bg-orange-500' },
  ARCHITECTURE: { label: 'Architecture', icon: Building2, color: 'bg-purple-500' },
}

function KnowledgePage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editDoc, setEditDoc] = useState<KnowledgeDocument | null>(null)
  const [viewDoc, setViewDoc] = useState<KnowledgeDocument | null>(null)

  const { data: documents, isLoading, error } = useQuery({
    queryKey: ['knowledge', 'organization'],
    queryFn: knowledgeApi.listOrganization,
  })

  const createMutation = useMutation({
    mutationFn: knowledgeApi.createOrganization,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'organization'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateKnowledgeDocumentRequest }) =>
      knowledgeApi.updateOrganization(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'organization'] })
      setEditDoc(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: knowledgeApi.deleteOrganization,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'organization'] })
    },
  })

  const activateMutation = useMutation({
    mutationFn: knowledgeApi.activateOrganization,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'organization'] })
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: knowledgeApi.deactivateOrganization,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge', 'organization'] })
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
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Organization Knowledge</h1>
          <p className="text-muted-foreground">
            Manage organization-wide standards, instructions, and requirements for all agents
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
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

      <Card>
        <CardHeader>
          <CardTitle>All Documents</CardTitle>
          <CardDescription>
            {documents?.length || 0} knowledge documents configured
          </CardDescription>
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
                      {doc.sourcePath && (
                        <div className="text-xs text-muted-foreground mt-1">
                          {doc.sourcePath}
                        </div>
                      )}
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
  const [priority, setPriority] = useState(document?.priority?.toString() || '50')
  const [appliesTo, setAppliesTo] = useState(document?.appliesTo?.join(', ') || '')
  const [sourcePath, setSourcePath] = useState(document?.sourcePath || '')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      category,
      content,
      priority: parseInt(priority, 10),
      appliesTo: appliesTo ? appliesTo.split(',').map((s) => s.trim()).filter(Boolean) : undefined,
      sourcePath: sourcePath || undefined,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>{document ? 'Edit Document' : 'Create Knowledge Document'}</DialogTitle>
        <DialogDescription>
          {document
            ? 'Update the knowledge document details.'
            : 'Add a new organization-wide knowledge document for agents.'}
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
              placeholder="e.g., Coding Standards"
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

        <div className="space-y-2">
          <Label htmlFor="sourcePath">Source Path (optional)</Label>
          <Input
            id="sourcePath"
            value={sourcePath}
            onChange={(e) => setSourcePath(e.target.value)}
            placeholder="e.g., docs/standards/coding.md"
          />
          <p className="text-xs text-muted-foreground">
            Original file path if synced from a repository.
          </p>
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
          {document.sourcePath && ` | Source: ${document.sourcePath}`}
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
