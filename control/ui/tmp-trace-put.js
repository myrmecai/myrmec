const fs = require('fs')
const raw = fs.readFileSync('test-results\\providers-admin-model-prov-c7b0c-th-inline-connection-config-chromium\\trace-unzipped\\0-trace.network', 'utf8')
const m = 'method":"PUT'
let idx = 0
for (let k = 0; k < 5; k++) {
  idx = raw.indexOf(m, idx + 1)
  if (idx < 0) break
  const start = raw.lastIndexOf('{', idx - 10)
  console.log('PUT at', idx)
  console.log(raw.substring(start, idx + 400))
}
