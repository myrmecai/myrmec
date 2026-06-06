import { useCallback, useEffect, useMemo } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  type Connection,
  type Edge,
  type Node,
  MarkerType,
  Panel,
} from '@xyflow/react'
import dagre from 'dagre'
import '@xyflow/react/dist/style.css'
import { StepNode, type StepNodeType } from './StepNode'
import { Button } from '@/components/ui/button'
import { Plus, LayoutGrid } from 'lucide-react'
import type { WorkflowStep } from '@/lib/api'

const nodeTypes = {
  step: StepNode,
}

// Edge colors for different transition types
const edgeColors: Record<string, string> = {
  success: '#22c55e',
  failure: '#ef4444',
  timeout: '#eab308',
  default: '#6b7280',
}

interface WorkflowCanvasProps {
  steps: WorkflowStep[]
  onStepsChange: (steps: WorkflowStep[]) => void
  onStepSelect: (stepId: string | null) => void
  selectedStepId: string | null
  readOnly?: boolean
  agentProfiles?: Array<{ id: string; name: string }>
  invalidStepIds?: Set<string>
}

// Auto-layout using dagre
function getLayoutedElements(
  nodes: StepNodeType[],
  edges: Edge[],
  direction = 'TB'
): { nodes: StepNodeType[]; edges: Edge[] } {
  const dagreGraph = new dagre.graphlib.Graph()
  dagreGraph.setDefaultEdgeLabel(() => ({}))
  dagreGraph.setGraph({ rankdir: direction, nodesep: 80, ranksep: 100 })

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: 200, height: 100 })
  })

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target)
  })

  dagre.layout(dagreGraph)

  const newNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id)
    return {
      ...node,
      position: {
        x: nodeWithPosition.x - 100,
        y: nodeWithPosition.y - 50,
      },
    }
  })

  return { nodes: newNodes, edges }
}

// Convert workflow steps to React Flow nodes and edges
function stepsToFlowElements(
  steps: WorkflowStep[],
  profiles: Array<{ id: string; name: string }>,
  invalidStepIds?: Set<string>
): { nodes: StepNodeType[]; edges: Edge[] } {
  const profileMap = new Map(profiles.map((p) => [p.id, p.name]))

  const nodes: StepNodeType[] = steps.map((step, index) => ({
    id: step.id,
    type: 'step',
    position: { x: 0, y: index * 150 },
    data: {
      ...step,
      agentProfileName: profileMap.get(step.agentProfileId) || 'Unknown',
      invalid: invalidStepIds?.has(step.id) ?? false,
    },
  }))

  const edges: Edge[] = []

  steps.forEach((step) => {
    // Add edges from dependsOn (implicit SUCCESS transitions)
    step.dependsOn?.forEach((depId) => {
      edges.push({
        id: `${depId}-${step.id}`,
        source: depId,
        target: step.id,
        sourceHandle: 'success',
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed },
        style: { stroke: edgeColors.success, strokeWidth: 2 },
        label: 'success',
        labelStyle: { fill: edgeColors.success, fontSize: 10 },
      })
    })

    // Add edges from explicit transitions
    if (step.transitions) {
      Object.entries(step.transitions).forEach(([transType, targetId]) => {
        // Skip if already added via dependsOn
        if (transType === 'SUCCESS' && step.dependsOn?.includes(targetId)) return

        const color = edgeColors[transType.toLowerCase()] || edgeColors.default
        edges.push({
          id: `${step.id}-${transType}-${targetId}`,
          source: step.id,
          target: targetId,
          sourceHandle: transType.toLowerCase(),
          type: 'smoothstep',
          markerEnd: { type: MarkerType.ArrowClosed },
          style: { stroke: color, strokeWidth: 2 },
          label: transType.toLowerCase(),
          labelStyle: { fill: color, fontSize: 10 },
        })
      })
    }
  })

  return getLayoutedElements(nodes, edges)
}

export function WorkflowCanvas({
  steps,
  onStepsChange,
  onStepSelect,
  selectedStepId,
  readOnly = false,
  agentProfiles = [],
  invalidStepIds,
}: WorkflowCanvasProps) {
  const initialElements = useMemo(
    () => stepsToFlowElements(steps, agentProfiles, invalidStepIds),
    [steps, agentProfiles, invalidStepIds]
  )

  const [nodes, setNodes, onNodesChange] = useNodesState(initialElements.nodes as Node[])
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialElements.edges)

  // Update nodes when steps change externally
  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = stepsToFlowElements(
      steps,
      agentProfiles,
      invalidStepIds
    )
    setNodes(newNodes as Node[])
    setEdges(newEdges)
  }, [steps, agentProfiles, invalidStepIds, setNodes, setEdges])

  const onConnect = useCallback(
    (connection: Connection) => {
      if (readOnly) return

      const sourceHandle = connection.sourceHandle || 'success'
      const color = edgeColors[sourceHandle] || edgeColors.default

      const newEdge: Edge = {
        ...connection,
        id: `${connection.source}-${sourceHandle}-${connection.target}`,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed },
        style: { stroke: color, strokeWidth: 2 },
        label: sourceHandle,
        labelStyle: { fill: color, fontSize: 10 },
      }

      setEdges((eds) => addEdge(newEdge, eds))

      // Update the step's transitions or dependsOn
      const updatedSteps = steps.map((step) => {
        if (step.id === connection.source) {
          if (sourceHandle === 'success') {
            return {
              ...step,
              transitions: {
                ...step.transitions,
                SUCCESS: connection.target!,
              },
            }
          } else {
            return {
              ...step,
              transitions: {
                ...step.transitions,
                [sourceHandle.toUpperCase()]: connection.target!,
              },
            }
          }
        }
        // If it's a success connection, also add to target's dependsOn
        if (step.id === connection.target && sourceHandle === 'success') {
          return {
            ...step,
            dependsOn: [...(step.dependsOn || []), connection.source!],
          }
        }
        return step
      })

      onStepsChange(updatedSteps)
    },
    [readOnly, steps, onStepsChange, setEdges]
  )

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onStepSelect(node.id)
    },
    [onStepSelect]
  )

  const onEdgesDelete = useCallback(
    (deleted: Edge[]) => {
      if (readOnly) return
      let updated = steps
      for (const edge of deleted) {
        const handle = (edge.sourceHandle || 'success').toUpperCase()
        updated = updated.map((step) => {
          if (step.id === edge.source) {
            const nextTransitions = { ...(step.transitions || {}) }
            if (nextTransitions[handle] === edge.target) {
              delete nextTransitions[handle]
            }
            return { ...step, transitions: nextTransitions }
          }
          if (step.id === edge.target && handle === 'SUCCESS') {
            return {
              ...step,
              dependsOn: (step.dependsOn || []).filter((d) => d !== edge.source),
            }
          }
          return step
        })
      }
      onStepsChange(updated)
    },
    [readOnly, steps, onStepsChange]
  )

  const onPaneClick = useCallback(() => {
    onStepSelect(null)
  }, [onStepSelect])

  const handleAddStep = useCallback(() => {
    const newStepId = `step-${steps.length + 1}`
    const newStep: WorkflowStep = {
      id: newStepId,
      name: `New Step ${steps.length + 1}`,
      agentProfileId: agentProfiles[0]?.id || '',
      dependsOn: [],
      transitions: {},
      timeoutSeconds: 300,
      maxRetries: 0,
    }
    onStepsChange([...steps, newStep])
    onStepSelect(newStepId)
  }, [steps, agentProfiles, onStepsChange, onStepSelect])

  const handleAutoLayout = useCallback(() => {
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(
      nodes as StepNodeType[],
      edges
    )
    setNodes(layoutedNodes as Node[])
    setEdges(layoutedEdges)
  }, [nodes, edges, setNodes, setEdges])

  // Highlight selected node
  const nodesWithSelection = useMemo(
    () =>
      nodes.map((node) => ({
        ...node,
        selected: node.id === selectedStepId,
      })),
    [nodes, selectedStepId]
  )

  return (
    <div className="h-full w-full">
      <ReactFlow
        nodes={nodesWithSelection}
        edges={edges}
        onNodesChange={readOnly ? undefined : onNodesChange}
        onEdgesChange={readOnly ? undefined : onEdgesChange}
        onConnect={onConnect}
        onEdgesDelete={readOnly ? undefined : onEdgesDelete}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        nodeTypes={nodeTypes}
        fitView
        snapToGrid
        snapGrid={[15, 15]}
        deleteKeyCode={readOnly ? null : 'Delete'}
        className="bg-gray-50"
      >
        <Background gap={15} size={1} />
        <Controls />
        <MiniMap
          nodeStrokeWidth={3}
          pannable
          zoomable
          className="bg-white border rounded"
        />

        {!readOnly && (
          <Panel position="top-left" className="flex gap-2">
            <Button size="sm" onClick={handleAddStep}>
              <Plus className="h-4 w-4 mr-1" />
              Add Step
            </Button>
            <Button size="sm" variant="outline" onClick={handleAutoLayout}>
              <LayoutGrid className="h-4 w-4 mr-1" />
              Auto Layout
            </Button>
          </Panel>
        )}
      </ReactFlow>
    </div>
  )
}
