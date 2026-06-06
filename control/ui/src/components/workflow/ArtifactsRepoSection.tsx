import { useQuery } from '@tanstack/react-query'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { projectSecretsApi, type ArtifactsRepo } from '@/lib/api'

interface ArtifactsRepoSectionProps {
  projectId: string
  value: ArtifactsRepo | null
  readOnly?: boolean
  onChange: (value: ArtifactsRepo | null) => void
}

const GIT_SECRET_TYPES = new Set(['USERNAME_PASSWORD', 'BEARER_TOKEN'])

export function ArtifactsRepoSection({
  projectId,
  value,
  readOnly = false,
  onChange,
}: ArtifactsRepoSectionProps) {
  const enabled = value !== null

  const { data: secrets } = useQuery({
    queryKey: ['project-secrets', projectId],
    queryFn: () => projectSecretsApi.list(projectId),
    enabled: enabled && !!projectId,
    staleTime: 5 * 60 * 1000,
  })

  const eligibleSecrets =
    secrets?.filter((s) => GIT_SECRET_TYPES.has(s.type)) ?? []

  const update = (patch: Partial<ArtifactsRepo>) => {
    onChange({
      url: value?.url ?? '',
      baseBranch: value?.baseBranch ?? null,
      credentialSecretId: value?.credentialSecretId ?? null,
      ...patch,
    })
  }

  return (
    <div className="space-y-2">
      <label className="flex items-center gap-2 text-xs">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => {
            if (e.target.checked) {
              onChange({
                url: '',
                baseBranch: 'main',
                credentialSecretId: null,
              })
            } else {
              onChange(null)
            }
          }}
          disabled={readOnly}
        />
        <span>Override artifacts repository</span>
      </label>

      {!enabled && (
        <p className="text-xs text-muted-foreground">
          Uses the project default artifacts repository.
        </p>
      )}

      {enabled && (
        <div className="space-y-2 pl-1">
          <div className="space-y-1">
            <Label className="text-xs">Repository URL</Label>
            <Input
              value={value?.url ?? ''}
              onChange={(e) => update({ url: e.target.value })}
              placeholder="https://github.com/org/repo.git"
              className="h-8 text-xs"
              disabled={readOnly}
            />
          </div>

          <div className="space-y-1">
            <Label className="text-xs">Base branch</Label>
            <Input
              value={value?.baseBranch ?? ''}
              onChange={(e) =>
                update({ baseBranch: e.target.value || null })
              }
              placeholder="main"
              className="h-8 text-xs"
              disabled={readOnly}
            />
          </div>

          <div className="space-y-1">
            <Label className="text-xs">Credential</Label>
            <Select
              value={value?.credentialSecretId ?? '__none__'}
              onValueChange={(v) =>
                update({ credentialSecretId: v === '__none__' ? null : v })
              }
              disabled={readOnly}
            >
              <SelectTrigger className="h-8 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">
                  None (unauthenticated)
                </SelectItem>
                {eligibleSecrets.map((s) => (
                  <SelectItem key={s.id} value={s.id}>
                    {s.name} ({s.type})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {eligibleSecrets.length === 0 && (
              <p className="text-[11px] text-muted-foreground">
                No git credentials in this project. Add a USERNAME_PASSWORD or
                BEARER_TOKEN secret on the project.
              </p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
