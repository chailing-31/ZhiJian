import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
const root = fileURLToPath(new URL('../src', import.meta.url))
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]) }
let checked = 0
for (const file of walk(root).filter(p => p.endsWith('.vue'))) {
  const source = fs.readFileSync(file, 'utf8')
  const { descriptor, errors } = parse(source, { filename: file })
  if (errors.length) throw new Error(`${file}: ${errors.join('\n')}`)
  const id = `ui-${checked}`
  const script = descriptor.script || descriptor.scriptSetup ? compileScript(descriptor, { id }) : null
  if (!descriptor.template) throw new Error(`${file}: missing template`)
  const result = compileTemplate({ source: descriptor.template.content, filename: file, id, compilerOptions: { bindingMetadata: script?.bindings || {} } })
  if (result.errors.length) throw new Error(`${file}: ${result.errors.join('\n')}`)
  checked++
}
console.log(`Vue SFC compilation checks passed: ${checked} files`)
