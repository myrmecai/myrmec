// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  projectsApi,
  groupsApi,
  type Project,
  type UpdateProjectRequest,
  type Group,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { FolderOpen, FolderX, ArrowLeft } from 'lucide-react'
import { ProjectMembers } from './ProjectMembers'
import { ProjectSecrets } from './ProjectSecrets'
import { ProjectAIContext } from './ProjectAIContext'

export function ProjectDetail({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState<'details' | 'members' | 'secrets' | 'ai-context' | 'audit'>('details')

  const { data: project, isLoading } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const { data: groups } = useQuery({
    queryKey: ['groups'],
    queryFn: groupsApi.list,
  })

  const groupById = new Map<string, Group>((groups ?? []).map((g) => [g.id, g]))

  const updateMutation = useMutation({
    mutationFn: (data: UpdateProjectRequest) => projectsApi.update(projectId, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
  })

  if (isLoading || !project) {
    return <div className="p-8 text-muted-foreground">Loading project…</div>
  }

  return (
    <ContentAreaLayout maxWidth="56rem">
      {/* Breadcrumb */}
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/projects" className="hover:underline">Projects</Link>
        <span>/</span>
        <span className="text-foreground font-medium">{project.name}</span>
      </div>

      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">{project.name}</h1>
          {project.description && <p className="text-muted-foreground">{project.description}</p>}
        </div>
        <div className="flex items-center gap-2">
          {project.status === 'ACTIVE' ? (
            <Badge className="bg-green-500 text-white">Active</Badge>
          ) : (
            <Badge variant="secondary">Inactive</Badge>
          )}
        </div>
      </div>

      {/* Tab headers */}
      <div className="flex gap-4 border-b mb-4">
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'details' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('details')}
        >General Details</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'members' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('members')}
        >Members</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'secrets' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('secrets')}
        >Secrets</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'ai-context' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('ai-context')}
        >AI Context</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'audit' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('audit')}
        >Audit Log</button>
      </div>

      {/* Tab content */}
      {activeTab === 'details' && (
        <GeneralDetailsTab
          project={project}
          groupName={groupById.get(project.groupId)?.name ?? '—'}
          onSave={(data) => updateMutation.mutate(data)}
          isSaving={updateMutation.isPending}
        />
      )}

      {activeTab === 'members' && <ProjectMembers projectId={projectId} />}

      {activeTab === 'secrets' && <ProjectSecrets projectId={projectId} />}

      {activeTab === 'ai-context' && <ProjectAIContext projectId={projectId} />}

      {activeTab === 'audit' && (
        <Card>
          <CardHeader><CardTitle className="text-base">Audit Log</CardTitle></CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-center py-4">Audit log entries will appear here.</p>
          </CardContent>
        </Card>
      )}
    </ContentAreaLayout>
  )
}

// --- General Details Tab ---

function GeneralDetailsTab({
  project,
  groupName,
  onSave,
  isSaving,
}: {
  project: Project
  groupName: string
  onSave: (data: UpdateProjectRequest) => void
  isSaving: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description || '')
  const [status, setStatus] = useState(project.status)
  const [allowedServiceTypes, setAllowedServiceTypes] = useState<string[]>(
    project.allowedServiceTypes ?? ['WORKFLOW', 'CONVERSATIONAL'],
  )
  const [workspaceRepoUrl, setWorkspaceRepoUrl] = useState(project.workspaceRepoUrl || '')
  const [workspaceRepoBranch, setWorkspaceRepoBranch] = useState(project.workspaceRepoBranch || 'main')

  const handleSave = () => {
    onSave({
      name,
      description: description || undefined,
      status,
      allowedServiceTypes,
      workspaceRepoUrl: workspaceRepoUrl || undefined,
      workspaceRepoBranch: workspaceRepoBranch || undefined,
    })
    setEditing(false)
  }

  if (!editing) {
    return (
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">Project Details</CardTitle>
            <Button size="sm" variant="outline" onClick={() => setEditing(true)}>Edit</Button>
          </div>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Name</dt><dd>{project.name}</dd></div>
            <div><dt className="text-muted-foreground">Description</dt><dd>{project.description || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Group</dt><dd>{groupName}</dd></div>
            <div><dt className="text-muted-foreground">Status</dt><dd>{project.status}</dd></div>
            <div><dt className="text-muted-foreground">Allowed Service Types</dt><dd>{(project.allowedServiceTypes ?? []).join(', ') || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Workspace Repo URL</dt><dd>{project.workspaceRepoUrl || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Workspace Repo Branch</dt><dd>{project.workspaceRepoBranch || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(project.createdAt).toLocaleString()}</dd></div>
          </dl>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Edit Project Details</CardTitle>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="proj-name">Name</Label>
          <Input id="proj-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="proj-desc">Description</Label>
          <Textarea id="proj-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="proj-active"
            checked={status === 'ACTIVE'}
            onChange={(e) => setStatus(e.target.checked ? 'ACTIVE' : 'INACTIVE')}
            className="h-4 w-4"
          />
          <Label htmlFor="proj-active">Active</Label>
        </div>
        <div className="space-y-2">
          <Label>Allowed Service Types</Label>
          <div className="space-y-2">
            {[
              { value: 'WORKFLOW', label: 'Workflow' },
              { value: 'CONVERSATIONAL', label: 'Conversational' },
            ].map((opt) => {
              const checked = allowedServiceTypes.includes(opt.value)
              const isLastChecked = checked && allowedServiceTypes.length === 1
              return (
                <label key={opt.value} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={isLastChecked}
                    onChange={(e) => {
                      if (e.target.checked) setAllowedServiceTypes([...allowedServiceTypes, opt.value])
                      else setAllowedServiceTypes(allowedServiceTypes.filter((v) => v !== opt.value))
                    }}
                    className="h-4 w-4"
                  />
                  {opt.label}
                </label>
              )
            })}
          </div>
        </div>
        <div className="border-t pt-4 space-y-3">
          <Label className="text-sm font-medium">Default Workspace Repository</Label>
          <div className="space-y-2">
            <Label htmlFor="proj-repo-url" className="text-sm">Repository URL</Label>
            <Input id="proj-repo-url" value={workspaceRepoUrl} onChange={(e) => setWorkspaceRepoUrl(e.target.value)} placeholder="https://github.com/org/repo.git" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="proj-repo-branch" className="text-sm">Branch</Label>
            <Input id="proj-repo-branch" value={workspaceRepoBranch} onChange={(e) => setWorkspaceRepoBranch(e.target.value)} placeholder="main" />
          </div>
        </div>
        <div className="flex gap-2">
          <Button size="sm" onClick={handleSave} disabled={isSaving}>
            {isSaving ? 'Saving…' : 'Save'}
          </Button>
          <Button size="sm" variant="outline" onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      </CardContent>
    </Card>
  )
}