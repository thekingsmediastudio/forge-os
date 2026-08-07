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
          panel3: "#1f1f23",
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
          danger: "#f87171",
        },
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
        mono: ["JetBrains Mono", "Consolas", "monospace"],
      },
      boxShadow: {
        card: "inset 0 1px 0 rgba(255,255,255,0.04), 0 14px 34px rgba(0,0,0,0.22)",
        pop: "0 8px 24px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.05)",
        glow: "0 10px 28px rgba(255,107,61,0.22)",
        "glow-lg": "0 18px 54px rgba(255,107,61,0.30)",
        "inner-hi": "inset 0 1px 0 rgba(255,255,255,0.12)",
      },
      backgroundImage: {
        "forge-ambient":
          "radial-gradient(circle at top left, rgba(255,107,61,0.10), transparent 30%), radial-gradient(circle at top right, rgba(96,165,250,0.07), transparent 26%), linear-gradient(180deg, #0b0b0e 0%, #09090b 100%)",
        "accent-grad": "linear-gradient(135deg, #ff7a4d 0%, #ff6b3d 55%, #f0522a 100%)",
        "panel-sheen": "linear-gradient(180deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0) 40%)",
      },
      keyframes: {
        "fade-up": {
          from: { opacity: "0", transform: "translateY(8px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        "pulse-dot": {
          "0%, 100%": { opacity: "1", transform: "scale(1)" },
          "50%": { opacity: "0.45", transform: "scale(0.82)" },
        },
        "logo-glow": {
          "0%, 100%": { boxShadow: "0 10px 28px rgba(255,107,61,0.22)" },
          "50%": { boxShadow: "0 14px 44px rgba(255,107,61,0.45)" },
        },
        "typing-bounce": {
          "0%, 60%, 100%": { transform: "translateY(0)", opacity: "0.4" },
          "30%": { transform: "translateY(-4px)", opacity: "1" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-400px 0" },
          "100%": { backgroundPosition: "400px 0" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.3s cubic-bezier(0.2, 0.8, 0.2, 1) both",
        "pulse-dot": "pulse-dot 1.6s ease-in-out infinite",
        "logo-glow": "logo-glow 3.2s ease-in-out infinite",
        "typing-1": "typing-bounce 1.2s ease-in-out 0s infinite",
        "typing-2": "typing-bounce 1.2s ease-in-out 0.15s infinite",
        "typing-3": "typing-bounce 1.2s ease-in-out 0.3s infinite",
        shimmer: "shimmer 1.6s linear infinite",
      },
      letterSpacing: {
        tightest: "-0.03em",
      },
    },
  },
  plugins: [],
};
