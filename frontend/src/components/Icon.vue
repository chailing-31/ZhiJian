<script setup>
defineProps({ name: { type: String, default: 'box' } })
const paths = {
  dashboard: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  box: 'M3 7l9-4 9 4v10l-9 4-9-4z M3 7l9 4 9-4 M12 11v10 M7 5l10 4',
  scan: 'M8 3H3v5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M7 8h10v8H7z M7 12h10',
  sliders: 'M3 6h5 M12 6h9 M3 12h11 M18 12h3 M3 18h3 M10 18h11 M8 3v6 M14 9v6 M6 15v6',
  thermometer: 'M9 5a3 3 0 016 0v9a5 5 0 11-6 0z M12 8v9 M18 5h3 M18 9h3',
  trace: 'M3 3h6v6H3z M15 3h6v6h-6z M3 15h6v6H3z M15 15h2v2h4v4h-6z M12 3v5 M3 12h5 M12 12h4 M12 18v3 M20 12h1',
  arrow: 'M4 12h16 M14 6l6 6-6 6',
  plus: 'M12 5v14 M5 12h14',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0112-1l2 3 M4 15l2 3a7 7 0 0012-1',
  clock: 'M12 8v5l3 2 M21 12a9 9 0 11-18 0 9 9 0 0118 0',
  leaf: 'M20 4c-9-2-16 2-16 9a7 7 0 0011 6c5-4 5-10 5-15z M5 19L16 8',
  search: 'M21 21l-5-5 M18 10a8 8 0 11-16 0 8 8 0 0116 0',
  check: 'M5 12l4 4L19 6',
  close: 'M6 6l12 12 M6 18L18 6',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  upload: 'M12 16V3 M7 8l5-5 5 5 M4 15v5h16v-5',
  alert: 'M12 3L2 21h20L12 3z M12 9v5 M12 17h.01',
  chevron: 'M9 5l7 7-7 7',
  back: 'M20 12H4 M10 6l-6 6 6 6',
  info: 'M12 11v6 M12 7h.01 M21 12a9 9 0 11-18 0 9 9 0 0118 0',
  file: 'M14 2H5v20h14V7l-5-5z M14 2v6h5 M8 12h8 M8 16h8',
  shield: 'M12 2l8 3v7c0 5-8 10-8 10S4 17 4 12V5l8-3z M8 12l3 3 5-6',
}
</script>
<template><svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.box" /></svg></template>
