// The Build tab: the tools and the blueprint quote. The track and station tools' options are on the
// map while the tool is on (ToolBar.tsx, T-2282).

import { inNativeEditor } from "../game/nativeEditor";
import { tool } from "../game/ui";
import { t } from "../i18n";
import { BuildGlyph } from "./BuildGlyph";
import { BlueprintControls, BlueprintPick } from "./Blueprint";
import { BUS_KEY, FERRY_KEY, pickTool, TOOLS } from "./toolKeys";
import { newBusLine, newFerryLine } from "../game/lineActions";
import { live } from "../map/live";

/** The building tools, then the blueprint's quote and construction. */
export function BuildTab() {
  const cur = tool.value;
  const standard = TOOLS.map(([id, label, , key, tip]) => ({
    id, label, key, title: tip.replace(/\n|$/, ` (${key})$&`), pick: () => pickTool(id),
  }));
  const [track, station, remove, select] = standard;
  const bus = { id: "bus", label: t("buildTab.bus"), key: BUS_KEY.toUpperCase(),
    title: t("buildTab.busTip", { key: BUS_KEY.toUpperCase() }),
    pick: () => cur === "bus" ? live.tools?.exit() : newBusLine() };
  const ferry = { id: "ferry", label: t("buildTab.ferry"), key: FERRY_KEY.toUpperCase(),
    title: t("buildTab.ferryTip", { key: FERRY_KEY.toUpperCase() }),
    pick: () => cur === "ferry" ? live.tools?.exit() : newFerryLine() };
  return (
    <>
      <div class="build-options">
        <div class="group tool-pick">
          {[track, station, bus, ferry, select, remove].map(({id,label,key,title,pick}) => <button key={id} class={"btn text build-tile tool-"+id+(cur===id?" on":"")}
            data-guide={id==="track"?"track":id==="station"?"stations":undefined} aria-pressed={cur===id}
            data-tip={title} onClick={pick}>
            <BuildGlyph tool={id}/><span class="tool-label">{label}</span><kbd class="key muted">{key}</kbd>
          </button>)}
        </div>
        {inNativeEditor && <button class={"btn text" + (cur === "trip" ? " on" : "")}
          aria-pressed={cur === "trip"} onClick={() => cur === "trip" ? live.tools?.exit() : pickTool("trip")}>
          {t("help.exploreTrip")}
        </button>}
        <BlueprintPick />
      </div>
      <BlueprintControls />
    </>
  );
}
