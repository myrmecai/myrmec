import defaultTheme from "tailwindcss/defaultTheme";

export default {
  content: ["./src/**/*.{astro,html,js,jsx,md,mdx,ts,tsx}"] ,
  theme: {
    extend: {
      colors: {
        // legacy (kept for compatibility)
        ink: "#101010",
        paper: "#f9f4e8",
        ember: "#f59e0b",
        clay: "#cc5b3e",
        pine: "#17332f"
      },
      fontFamily: {
        sans: ["Inter", ...defaultTheme.fontFamily.sans],
        display: ["Inter", ...defaultTheme.fontFamily.sans],
        mono: ["JetBrains Mono", ...defaultTheme.fontFamily.mono]
      },
      boxShadow: {
        card: "0 18px 42px -24px rgba(0, 0, 0, 0.7)"
      }
    }
  },
  plugins: []
};
