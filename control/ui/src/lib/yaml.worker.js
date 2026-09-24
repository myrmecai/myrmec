// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
// Vite worker shim for monaco-yaml (documented workaround for the
// "Unexpected usage" / "Missing requestHandler" worker failure under
// Vite's ESM dev bundling): the worker entry must live in project
// source so `new Worker(..., import.meta.url)` bundling applies.
import 'monaco-yaml/yaml.worker.js'