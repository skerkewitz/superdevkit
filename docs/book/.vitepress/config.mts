import { defineConfig } from 'vitepress'
import { codeTitles } from './code-titles'

// The three guides of the book, each with a sidebar of its own (doc-specs/README.md).
const userGuide = [
  {
    text: 'User guide',
    items: [
      { text: 'What is dampf?', link: '/guide/introduction' },
      { text: 'Getting started', link: '/guide/getting-started' },
      { text: 'Writing C for dampf', link: '/guide/writing-c' },
      { text: 'Compiling a game', link: '/guide/compiling-a-game' },
      { text: 'Debugging in Mesen', link: '/guide/debugging' }
    ]
  }
]

const reference = [
  {
    text: 'Reference',
    items: [
      { text: 'Command line', link: '/reference/command-line' },
      { text: 'The C subset', link: '/reference/c-subset' },
      { text: 'Hints', link: '/reference/hints' },
      { text: 'Runtime and memory', link: '/reference/runtime-and-memory' },
      { text: 'The generated files', link: '/reference/output-files' },
      { text: 'Limits', link: '/reference/limits' },
      { text: 'Glossary', link: '/glossary' }
    ]
  }
]

const inside = [
  {
    text: 'Inside dampf',
    items: [
      { text: 'How a compile works', link: '/inside/how-a-compile-works' },
      { text: 'MIR', link: '/inside/mir' },
      { text: 'Optimizations', link: '/inside/optimizations' },
      { text: 'Testing', link: '/inside/testing' },
      { text: 'Roadmap', link: '/inside/roadmap' }
    ]
  }
]

// https://vitepress.dev/reference/site-config
export default defineConfig({
  title: 'dampf',
  description: 'A C compiler for the 65816 and the Super Nintendo',
  cleanUrls: true,
  lastUpdated: true,

  markdown: {
    config(md) {
      // A title bar with the file name over ```c [examples/m1/calls.c].
      md.use(codeTitles)

      // Hand ```mermaid blocks to the <Mermaid> component, which draws them in the browser.
      const fence = md.renderer.rules.fence!
      md.renderer.rules.fence = (tokens, idx, options, env, self) => {
        const token = tokens[idx]
        if (token.info.trim() === 'mermaid') {
          return `<Mermaid code="${encodeURIComponent(token.content)}" />`
        }
        return fence(tokens, idx, options, env, self)
      }
    }
  },

  themeConfig: {
    nav: [
      { text: 'User guide', link: '/guide/introduction', activeMatch: '^/guide/' },
      { text: 'Reference', link: '/reference/command-line', activeMatch: '^/(reference|glossary)' },
      { text: 'Inside', link: '/inside/how-a-compile-works', activeMatch: '^/inside/' },
      { text: 'Glossary', link: '/glossary' }
    ],

    // One sidebar per guide (doc-specs/README.md).
    sidebar: {
      '/guide/': userGuide,
      '/reference/': reference,
      '/glossary': reference,
      '/inside/': inside
    },

    outline: [2, 3],
    search: { provider: 'local' }
  }
})
