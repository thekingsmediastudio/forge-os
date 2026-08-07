/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./index.html", "./src/**/*.{js,jsx,ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // "Quiet Power" design system (see _FORGE_PLAN/redesigned-ui-mockup.html)
        forge: {
          bg: "#09090b",
          panel: "#111113",
          panel2: "#18181b",
          border: "#27272a",
          text: "#fafafa",
          body: "#d4d4d8",
          muted: "#a1a1aa",
          faint: "#52525b",
          accent: "#ff6b3d",
          accentHi: "#ff7a4d",
          accentSoft: "#ff8a5c",
          ok: "#34d399",
          warn: "#fbbf24",
          info: "#60a5fa",
        },
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
        mono: ["JetBrains Mono", "Consolas", "monospace"],
      },
      boxShadow: {
        card: "inset 0 1px 0 rgba(255,255,255,0.03), 0 14px 34px rgba(0,0,0,0.18)",
        glow: "0 10px 28px rgba(255,107,61,0.22)",
      },
      backgroundImage: {
        "forge-ambient":
          "radial-gradient(circle at top left, rgba(255,107,61,0.08), transparent 28%), radial-gradient(circle at top right, rgba(96,165,250,0.05), transparent 24%), linear-gradient(180deg, #0b0b0e 0%, #09090b 100%)",
      },
    },
  },
  plugins: [],
};
