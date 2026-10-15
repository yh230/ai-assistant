// .vue 静态语法检查：只读解析 SFC，不构建、不启动服务
// 用法：node scripts/check-sfc.mjs src/views/A.vue src/views/B.vue ...
import { readFileSync } from 'node:fs'
import { parse } from 'vue/compiler-sfc'

const files = process.argv.slice(2)
if (files.length === 0) {
  console.error('用法：node scripts/check-sfc.mjs <file.vue> [...]')
  process.exit(1)
}
let bad = 0
for (const f of files) {
  try {
    const { errors } = parse(readFileSync(f, 'utf8'), { filename: f })
    if (errors.length) {
      bad++
      console.error('FAIL', f, errors.map((e) => String(e)).join('\n'))
    } else {
      console.log('OK  ', f)
    }
  } catch (e) {
    bad++
    console.error('FAIL', f, e.message)
  }
}
process.exit(bad ? 1 : 0)