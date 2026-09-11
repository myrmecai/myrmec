// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Renames dist/cjs/*.js files to .cjs and mirrors them into dist,
 * then deletes the dist/cjs directory. This lets the dual-module
 * package.json exports point to sibling .js / .cjs files.
 */
const fs = require('fs')
const path = require('path')

const cjsDir = path.join(__dirname, '..', 'dist', 'cjs')
const distDir = path.join(__dirname, '..', 'dist')

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

if (fs.existsSync(cjsDir)) {
  walk(cjsDir, (src) => {
    if (!src.endsWith('.cjs')) return
    const rel = path.relative(cjsDir, src)
    const dest = path.join(distDir, rel)
    fs.mkdirSync(path.dirname(dest), { recursive: true })
    fs.copyFileSync(src, dest)
    const mapSrc = `${src}.map`
    if (fs.existsSync(mapSrc)) {
      const mapDest = `${dest}.map`
      fs.copyFileSync(mapSrc, mapDest)
      const map = JSON.parse(fs.readFileSync(mapDest, 'utf8'))
      map.file = path.basename(dest)
      fs.writeFileSync(mapDest, JSON.stringify(map))
    }
  })
  fs.rmSync(cjsDir, { recursive: true, force: true })
}
