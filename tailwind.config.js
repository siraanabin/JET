/** Tailwind CSS 3.4.17 configuration for JET's offline WebView UI. */
module.exports = {
  darkMode: 'class',
  content: ['./app/src/main/assets/**/*.html', './app/src/main/assets/**/*.js'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'system-ui', 'sans-serif'],
      },
      colors: {
        app: {
          bg: 'var(--bg-canvas)',
          surface: 'var(--bg-surface)',
          elevated: 'var(--bg-elevated)',
          border: 'var(--border-subtle)',
          primary: 'var(--accent-primary)',
          primaryHover: 'var(--accent-hover)',
          primaryTint: 'var(--accent-tint)',
          text: 'var(--text-primary)',
          secondary: 'var(--text-secondary)',
          muted: 'var(--text-muted)',
          success: 'var(--status-success)',
          warning: 'var(--status-warning)',
          danger: 'var(--status-danger)',
          nav: 'var(--bg-nav)',
        },
      },
    },
  },
  safelist: [
    // Runtime-selected semantic colors and gradient utilities used by library cards.
    { pattern: /^(bg|text|border|ring|from|via|to)-(amber|emerald|blue|indigo|slate|rose|red|green|yellow|sky|violet|purple|zinc|neutral|gray|orange|teal|cyan)-(50|100|200|300|400|500|600|700|800|900|950)(\/([0-9]{1,3}))?$/ },
    'bg-amber-500/15', 'text-app-warning', 'bg-emerald-500/15', 'text-app-success',
    'border-app-primary', 'bg-app-primaryTint/60', 'bg-app-surface', 'border-app-border',
    'bg-[#FACC15]', 'text-slate-950',
    'hidden', 'block', 'flex', 'inline-flex', 'grid', 'opacity-0', 'opacity-100',
    'pointer-events-none', 'pointer-events-auto', 'animate-spin', 'animate-pulse',
    'translate-y-0', 'translate-y-full', 'scale-95', 'scale-100',
  ],
};
