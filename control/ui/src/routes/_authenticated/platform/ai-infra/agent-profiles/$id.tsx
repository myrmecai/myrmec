import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute(
  '/_authenticated/platform/ai-infra/agent-profiles/$id',
)({
  component: RouteComponent,
})

function RouteComponent() {
  return (
    <div>Hello "/_authenticated/platform/ai-infra/agent-profiles/$id"!</div>
  )
}
