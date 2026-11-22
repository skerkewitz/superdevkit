// Shows the file a code block belongs to above it: ```c [compiler/src/test/resources/exec/calls.c].
// VitePress reads such a title only for the tabs of a ::: code-group and drops it elsewhere,
// so a block outside a group gets a title bar of its own, styled in custom.css.

export function codeTitles(md: any) {
  const fence = md.renderer.rules.fence!
  md.renderer.rules.fence = (tokens: any[], idx: number, options: any, env: any, self: any) => {
    // Read before rendering: VitePress's own fence rule removes the title from the info.
    const title = /\[([^\]]+)\]/.exec(tokens[idx].info)?.[1]
    const html = fence(tokens, idx, options, env, self)
    if (!title || inCodeGroup(tokens, idx)) {
      return html
    }
    return `<div class="code-titled"><div class="code-title">${md.utils.escapeHtml(title)}</div>${html}</div>`
  }
}

function inCodeGroup(tokens: any[], idx: number): boolean {
  for (let i = idx - 1; i >= 0; i--) {
    if (tokens[i].type === 'container_code-group_close') {
      return false
    }
    if (tokens[i].type === 'container_code-group_open') {
      return true
    }
  }
  return false
}
