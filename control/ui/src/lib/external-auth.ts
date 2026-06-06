const EXTERNAL_AUTH_STATE_KEY = 'myrmec_external_auth_states'

type ExternalAuthStateMap = Record<string, string>

function readStateMap(): ExternalAuthStateMap {
  try {
    const raw = sessionStorage.getItem(EXTERNAL_AUTH_STATE_KEY)
    if (!raw) {
      return {}
    }
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object') {
      return {}
    }
    return parsed as ExternalAuthStateMap
  } catch {
    return {}
  }
}

function writeStateMap(map: ExternalAuthStateMap) {
  sessionStorage.setItem(EXTERNAL_AUTH_STATE_KEY, JSON.stringify(map))
}

export function registerExternalAuthState(state: string, providerCode: string) {
  const normalizedState = state.trim()
  const normalizedProvider = providerCode.trim().toUpperCase()
  if (!normalizedState || !normalizedProvider) {
    return
  }

  const map = readStateMap()
  map[normalizedState] = normalizedProvider
  writeStateMap(map)
}

export function consumeExternalAuthProvider(state: string): string | null {
  const normalizedState = state.trim()
  if (!normalizedState) {
    return null
  }

  const map = readStateMap()
  const providerCode = map[normalizedState] ?? null
  if (!providerCode) {
    return null
  }

  delete map[normalizedState]
  writeStateMap(map)
  return providerCode
}

export interface ExternalCallbackParams {
  state: string | null
  code: string | null
  error: string | null
  errorDescription: string | null
}

export function readExternalCallbackParams(search: string): ExternalCallbackParams {
  const params = new URLSearchParams(search)
  return {
    state: params.get('state'),
    code: params.get('code'),
    error: params.get('error'),
    errorDescription: params.get('error_description'),
  }
}

export function clearExternalCallbackParams() {
  const url = new URL(window.location.href)
  url.searchParams.delete('state')
  url.searchParams.delete('code')
  url.searchParams.delete('error')
  url.searchParams.delete('error_description')
  window.history.replaceState({}, '', url.toString())
}
