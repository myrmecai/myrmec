const fs = require('fs')
const raw = fs.readFileSync('test-results\\providers-admin-model-prov-c7b0c-th-inline-connection-config-chromium\\trace-unzipped\\0-trace.trace', 'utf8')
const lines = raw.split('\n')
lines.forEach((line, idx) => {
  if (line.includes('console')) {
    const obj = JSON.parse(line)
    if (obj.type === 'console' && obj.messageType === 'error') {
      console.log(idx + 1, obj.text)
    }
  }
})
