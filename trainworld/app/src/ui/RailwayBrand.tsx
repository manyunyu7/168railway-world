// Original 168Railway World start-page art: two intersecting commuter lines.
import { Icon } from "./widgets";

export function RailwayBrand() {
  return <header class="railway-brand">
    <svg aria-hidden="true" class="railway-brand-lines" viewBox="0 0 400 108" preserveAspectRatio="xMidYMid slice">
      <g class="route-grid">
        <path d="M0 18h400M0 54h400M0 90h400M32 0v108M80 0v108M128 0v108M176 0v108M224 0v108M272 0v108M320 0v108M368 0v108" />
      </g>
      <path class="route-blue" d="M-14 97h94q9 0 16-7l29-29q7-7 17-7h117q13 0 21-9l31-31q8-9 21-9h85" />
      <path class="route-green" d="M-12 70h51q9 0 16-7l33-33q8-8 18-8h95q10 0 18 8l36 36q8 8 18 8h143" />
      <g class="route-stops"><circle cx="35" cy="70" r="5"/><circle cx="125" cy="22" r="5"/><circle cx="243" cy="54" r="8"/><circle cx="338" cy="74" r="5"/><circle cx="80" cy="97" r="5"/></g>
    </svg>
    <div class="railway-brand-title"><span class="railway-mark" aria-hidden="true"><Icon.train/></span><h1 id="start-title">168Railway <strong>World</strong></h1></div>
  </header>;
}
