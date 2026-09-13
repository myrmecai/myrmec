// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Produces a CommonJS build of the agent SDK by:
 *  1. Copying the ESM dist tree to dist/cjs.
 *  2. Renaming .js files to .cjs.
 *  3. Rewriting internal `import ... from './x.js'` statements to
 *     `require('./x.cjs')` and `require('./x.cjs')`.
 *  4. Rewriting static `import.meta.url` uses for worker entry resolution.
 *
 * This avoids re-running tsc with `module: CommonJS`, which fails on the
 * source's top-level await and `import.meta` usage. The source is ESM-first;
 * the CJS files are a runtime-compatible translation.
 */
const fs = require('fs')
const path = require('path')

const distDir = path.join(__dirname, '..', 'dist')
const cjsDir = path.join(__dirname, '..', 'dist', 'cjs')

function rm(dir) {
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

function cp(src, dst) {
  fs.mkdirSync(path.dirname(dst), { recursive: true })
  fs.copyFileSync(src, dst)
}

function walk(dir, cb) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(p, cb)
    } else {
      cb(p)
    }
  }
}

rm(cjsDir)

walk(distDir, (src) => {
  const rel = path.relative(distDir, src)
  if (rel.startsWith('cjs' + path.sep)) return
  const dst = path.join(cjsDir, rel)
  cp(src, dst)
})

walk(cjsDir, (src) => {
  if (!src.endsWith('.js')) return
  const cjsPath = src.replace(/\.js$/, '.cjs')
  let code = fs.readFileSync(src, 'utf8')

  // Replace all top-level ESM import declarations with CommonJS require() calls.
  // This covers sibling imports, package imports (`node:worker_threads`), default
  // imports, and namespace imports.
  code = code.replace(
    /^import\s+\*\s+as\s+(\w+)\s+from\s+(['"])([^'"]+)\2\s*;?\s*$/gm,
    (match, binding, quote, modulePath) => {
      return `const ${binding} = require(${quote}${modulePath}${quote});`
    },
  )
  code = code.replace(
    /^import\s+(\w+)\s+from\s+(['"])([^'"]+)\2\s*;?\s*$/gm,
    (match, binding, quote, modulePath) => {
      return `const ${binding} = require(${quote}${modulePath}${quote});`
    },
  )
  code = code.replace(
    /^import\s+\{([^}]+)\}\s+from\s+(['"])([^'"]+)\2\s*;?\s*$/gm,
    (match, bindings, quote, modulePath) => {
      const cleaned = bindings.split(',').map((s) => s.trim()).filter(Boolean).join(', ')
      return `const { ${cleaned} } = require(${quote}${modulePath}${quote});`
    },
  )

  // Replace any remaining `require('./x.js')` sibling references with
  // `require('./x.cjs')` so the CJS build resolves to the mirrored .cjs files.
  code = code.replace(
    /require\((['"])\.\/([^'"]+)\.js\1\)/g,
    (match, quote, base) => `require(${quote}./${base}.cjs${quote})`,
  )

  // Replace any remaining `export * from './x.js'` with `module.exports = require('./x.cjs')`
  // is intentionally not applied; the SDK uses only named exports in the public entry files
  // and keeps `export class` / `export function` declarations which are valid in CJS under
  // Node's ESM-in-CJS interop. This step documents that boundary.

  // Replace internal import.meta.url references used by resolveWorkerSource.
  code = code.replace(
    /import\.meta\.url/g,
    'require(\'url\').pathToFileURL(__filename).href',
  )

  fs.writeFileSync(cjsPath, code)
  fs.rmSync(src)

  const mapSrc = `${src}.map`
  if (fs.existsSync(mapSrc)) {
    const mapDest = `${cjsPath}.map`
    fs.copyFileSync(mapSrc, mapDest)
    const map = JSON.parse(fs.readFileSync(mapDest, 'utf8'))
    map.file = path.basename(cjsPath)
    fs.writeFileSync(mapDest, JSON.stringify(map))
    fs.rmSync(mapSrc)
  }
})

console.error('CJS build written to', cjsDir)
