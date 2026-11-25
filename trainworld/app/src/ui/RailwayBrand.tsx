// Original 168Railway World start-page art: two intersecting commuter lines.
import { Icon } from "./widgets";

/** `title` false: the lines and the loco alone, as the launcher card's top strip (StartMenu.tsx) */
export function RailwayBrand({ title = true }: { title?: boolean } = {}) {
  return <header class="railway-brand">
    <svg aria-hidden="true" class="railway-brand-lines" viewBox="0 0 400 108" preserveAspectRatio="xMidYMid slice">
      <g class="route-grid">
        <path d="M0 18h400M0 54h400M0 90h400M32 0v108M80 0v108M128 0v108M176 0v108M224 0v108M272 0v108M320 0v108M368 0v108" />
      </g>
      <path class="route-blue" d="M-14 97h94q9 0 16-7l29-29q7-7 17-7h117q13 0 21-9l31-31q8-9 21-9h85" />
      <path class="route-green" d="M-12 70h51q9 0 16-7l33-33q8-8 18-8h95q10 0 18 8l36 36q8 8 18 8h143" />
      <g class="route-stops"><circle cx="35" cy="70" r="5"/><circle cx="125" cy="22" r="5"/><circle cx="243" cy="54" r="8"/><circle cx="338" cy="74" r="5"/><circle cx="80" cy="97" r="5"/></g>
      <g transform="translate(247 3) rotate(-6 51 49)" class="brand-loco">
        <ellipse cx="54" cy="91" rx="48" ry="6" fill="#0a302a"/>
        <path d="m23 77-9 17m57-17 9 17" stroke="#a5c9bb" stroke-width="5"/>
        <path d="M8 31Q8 9 31 7h32q11 0 18 8l14 13v42q0 9-9 12L65 88H25Q8 87 8 72z" fill="#173f36" stroke="#0d362e" stroke-width="3"/>
        <path d="M65 16q12-3 19 6l8 9v33l-26 9z" fill="#26a874"/>
        <path d="M71 27l9 2v20l-9 3zm13 5 5 3v12l-5 2z" fill="#184d57"/>
        <rect x="8" y="9" width="65" height="74" rx="17" fill="#a6ed43"/>
        <rect x="19" y="19" width="43" height="8" rx="4" fill="#214a38"/>
        <path d="M20 32h40q4 0 4 4v15q0 6-6 6H22q-6 0-6-6V37q0-5 4-5z" fill="#13434d"/>
        <path d="M22 37h9l-8 12h-4z" fill="#42999c"/>
        <path d="M29 45q3-5 6 0m10 0q3-5 6 0" stroke="#e1ffee" stroke-width="3.2" fill="none"/>
        <path d="M8 63q32 6 65-1v8q-32 8-65-1z" fill="#f2fff2"/>
        <rect x="15" y="64" width="10" height="5" rx="2.5" fill="#ffe17b"/><rect x="56" y="64" width="10" height="5" rx="2.5" fill="#ffe17b"/>
        <path d="M31 74q9 6 18-1" fill="none" stroke="#245a39" stroke-width="3"/>
      </g>
    </svg>
    {title && <div class="railway-brand-title"><span class="railway-mark" aria-hidden="true"><Icon.train/></span><h1 id="start-title">168Railway <strong>World</strong></h1></div>}
  </header>;
}
