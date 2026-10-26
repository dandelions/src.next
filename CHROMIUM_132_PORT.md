# Chromium 132 forward port — assessment and recipe

Status: **feasibility assessed, not ported.** This branch contains no 132 code yet; it records
what was measured and what the porting recipe is, so the work does not have to be re-derived.

Measured on 2025-10-26 against `kiwibrowser/src.next`.

## "But upstream is on 137?" — no, it is 105 with a 137 label

Release `14310011181` (2025-04-07, the "latest published version" the README links to) is titled
**"Version 137.0.7337.0"** — but the engine behind it is Chromium **105**. Checked on 2025-10-26:

| check | result |
| --- | --- |
| source at tag `14310011181` (`32dfc33`) | `CHROMIUM_VERSION` = **105.0.5195.24**, `KIWI_VERSION` = **105.0.5195.33** |
| the same release body | "Updated the version (resolves *App not installed* during upgrade)" and "Updated the **user-agent to a future user-agent** to prevent websites from nagging about Chromium version" |
| real Chromium builds for those milestones (chromiumdash, Stable/Android) | 124 → `124.0.6367.x`, 132 → `132.0.6834.x`, 137 → `137.0.7151.x` |
| what Kiwi claimed | 124 → `124.0.**6327**.x`, 132 → `132.0.**6961**.0`, 137 → `137.0.**7337**.0` — none of these builds exist |
| all refs of `kiwibrowser/src.next` containing `137` | **none** |
| branches | `kiwi` (105 delta), `chromium` (132 mirror, 2025-01-20), `chromium_132.0.6834.83`, one dependabot branch |
| `chromium` branch HEAD | `c4ac1ae` — `[Chromium] Update version 107.0.5304.17 to 132.0.6834.83`, 2025-01-20 |
| `kiwibrowser` account repos | `src.next` is the only browser source repo; nothing on 137 |

So from the 124 release onwards the version string and the UA were bumped forward while the engine
stayed at 105 — the 137 release notes say as much ("Kiwi will no longer receive engine updates").
The build numbers also give it away: the 132 mirror branch is named after the **real** build
`132.0.6834.83`, while the 132 *release* claimed `132.0.6961.0`. Nobody forked "137" wrongly:
there is no 137 source to fork, only a relabelled 105 build.

Forks of `src.next` that are not on 105 either mirror 132 or are independent revivals — not official
Kiwi:

| repo | `CHROMIUM_VERSION` | note |
| --- | --- | --- |
| `BigRay1337/kiwi-browser-extension` | 137.0.7337.0 | metadata-only repo; repeats the 137 label, no source tree |
| `raihanbhaii/kiwi-reborn` | 148.0.7778.96 | independent revival |
| `danosito/afterbird` | 151.0.7922.38 | independent revival |
| `Facilitat-dev/kiwi-browser` | 153.0.8010.39 | "Kiwi lineage, Afterbird engine", has `cursor/lemur-*` branches |
| `Relixor/Relixor`, `pufferfish101007/kiwibrowser`, `misterfrankw/src.next`, `ferelking242/eterna-browser`, `GPLCodeHub/browser.next` | 105.0.5195.24 | plain forks |

A fork lacking `chromium` / `chromium_132.0.6834.83` costs nothing: add the upstream remote and fetch,
no re-fork needed.

```bash
git remote add upstream https://github.com/kiwibrowser/src.next.git
git fetch --unshallow --filter=blob:none upstream chromium   # ~10k files, ~100 MB
```

## What upstream actually provides

| ref | content | version |
| --- | --- | --- |
| `kiwi` | Kiwi delta, 8 316 files | Chromium **105.0.5195.24** (`KIWI_VERSION` 105.0.5195.33) |
| `chromium` | *vanilla snapshot of the 9 691 files Kiwi touches* — not a full source tree | Chromium **132.0.6834.83** |
| `chromium_132.0.6834.83` | same content as `chromium`, pinned by name | Chromium **132.0.6834.83** |

`chromium` has **no `KIWI_VERSION`**, i.e. the 132 base was prepared but the Kiwi delta was never
rebased onto it — the repository was archived at that point. `chromium` history is a chain of
`[Chromium] Update version X to Y` commits (197 commits), and the `--filter=blob:none` mirror is
what makes `git rebase upstream/chromium` practical: only ~10 k files are involved, not ~400 k.

Two important consequences:

* the rebase mechanism the upstream workflow (`rebase_kiwi_on_top_of_chromium.yml`) uses is
  intact and available in a fork;
* the 132 base is *not* a buildable Chromium checkout either — it is a delta mirror, exactly like
  this repository.

## Measured merge cost (not a guess)

```bash
git fetch --unshallow --filter=blob:none upstream chromium
git merge-tree --write-tree --name-only \
    --merge-base=7011172b upstream/chromium kiwi     # 7011172b = vanilla 105 snapshot
```

| metric | value |
| --- | --- |
| Kiwi delta paths | 8 316 |
| same path still exists at 132 | 8 121 (**97.7 %**) |
| path removed / moved at 132 | 195 (2.3 % — mostly `res/drawable-*` and `res_chromium_contributor/mipmap-*`, plus 6 Java files such as `TabSwitcherSettings.java`, `TranslateSettings.java`) |
| **content conflicts** | **166 files (~2.0 %)** — 51 Java, 7 XML, 108 other |
| top conflicting areas | `chrome/browser/ui/android` 27, `chrome/android/java/src` 13, `third_party/blink/renderer/core` 9, `chrome/android/features/tab_ui` 8 |

Conflicting files include `AppMenuHandlerImpl.java`, `AppMenuPropertiesDelegateImpl.java`,
`main_menu.xml`, `ChromeTabbedActivity.java`.
Clean (auto-mergeable) and relevant to this feature: `TabbedAppMenuPropertiesDelegate.java`,
`menu_button.xml`.

**Auto-merge is not compilation.** 2 % textual conflict is a *lower bound* on effort; the real cost
is 27 versions of API drift in files that merge textually but no longer compile. Verified examples
on the 132 base:

* `AppMenuHandlerImpl` grew 483 → **616 lines**; `showAppMenu(View anchorView, boolean startDragging)`
  keeps its signature, but the model list is now assigned to the field and a `mListObserver` is
  attached (`mModelList.addObserver(mListObserver)`), and the keyboard delegate moved to
  `mWindowAndroid.getKeyboardDelegate()`;
* `AppMenuPropertiesDelegateImpl` gained `getMenuItemsForMenu(...)` next to `getMenuItems(...)`
  (1 401 lines at 132);
* the whole extensions story changed: Chromium 127+ disables **Manifest V2**, which is Kiwi's main
  selling point (`AppMenuBridge.getRunningExtensions`, MV2 uBlock Origin). It is not a merge
  conflict, but it looked like a product-level blocker — see the note below for why it is smaller
  than it first appeared.

### The MV2 blocker is smaller than it looks

`Facilitat-dev/kiwi-browser` (a Kiwi-lineage rebuild on Chromium 153.0.8010.39) ships
`patches/m153/0001-mv2-reenable.patch`: 13 lines, one file, `extensions/browser/manifest_v2_util.cc`,
where `IsExtensionAffected()` returns `false` unconditionally instead of flagging MV2 extensions as
deprecated. Its `0003` patch (27 lines) similarly fixes the phone-form-factor crash where
`getExtensionsToolbarCoordinator()` returns null, by falling back to `chrome://extensions`.

So MV2 does not decide the port. What it costs: you take ownership of the deprecation policy and
have to follow MV2 security guidance yourself.

Reimplement both changes from the described behaviour rather than copying the patch text — that
repository is BSD-3-Clause, but `patches/legacy-kiwi/` inside it is GPL-3.0 and its own NOTICE says
not to mix that into a BSD-only tree.

## Verdict

* **Yes, a 132 port is mechanically possible** — the base exists, the rebase chain exists, 98 % of
  paths survive, and the conflict count is small (166 files).
* **No, it is not a one-afternoon rebase** — expect a compile-fix campaign over the 8 121
  auto-merged files. MV2 is recoverable with a ~13-line patch, so extensions are no longer the
  deciding factor; the deciding factor is the API drift across 27 milestones.
* The Lemur UI port itself is *not* the problem; see below.

## Lemur UI on 132 — already validated at patch level

`patch_lemur_app_menu.py` on this branch is version-agnostic: it hooks `showAppMenu()` at whichever
anchor exists and was verified to apply cleanly to both trees.

| anchor | Chromium 105 (Kiwi) | Chromium 132 |
| --- | --- | --- |
| model list | `mModelList = modelList;` → hook **after** | `mModelList.addObserver(mListObserver);` → hook **before** (so no observer needs unregistering on dismiss) |
| `isAppMenuShowing()` body | `return mAppMenu != null && mAppMenu.isShowing();` | identical |
| `hideAppMenu()` body | single-line `dismiss()` | multi-line + observer removal → patched by **line insertion**, not block replace |
| `menu_button.xml` icon | `android:src="@drawable/ic_more_vert_24dp"` | identical |

Smoke test used: the raw 132 `AppMenuHandlerImpl.java` and `menu_button.xml` were fetched from
`raw.githubusercontent.com/kiwibrowser/src.next/c4ac1ae/...` into a scratch tree and the patcher was
run against it — hook 1×, methods 1×, braces/parens balanced (128/128, 556/556), 1 089 lines.

Still unverified there: `AppMenuItemProperties` key set, `AppMenuItemType.*_BUTTON_ROW` values, and
the `R.id`-free design means no new id dependencies — those must be confirmed by a real build.

## Recipe

```bash
# 1. get the 132 mirror (partial clone keeps this ~100 MB instead of a full Chromium)
git remote add upstream https://github.com/kiwibrowser/src.next.git
git fetch --unshallow --filter=blob:none upstream chromium

# 2. dry-run the conflict surface before touching anything
git merge-tree --write-tree --name-only --merge-base=7011172b upstream/chromium kiwi

# 3. port the delta onto 132
git checkout -b kiwi-132 kiwi
git rebase --onto upstream/chromium 7011172b
#    ... resolve the ~166 conflicts, then compile-fix the auto-merged files

# 4. re-apply the Lemur UI on the new tree
python3 patch_lemur_app_menu.py <path-to>/chromium/src

# 5. tag once a build is green
git tag -a v0.2.x-cr132.0.6834.83 -m "Kiwi delta + Lemur UI on Chromium 132"
```

Do **not** push a branch that contains the full 132 mirror into this fork: it duplicates ~10 k
upstream blobs. Keep the mirror in `upstream/` and push only the delta commits on top.

## Version tags

| tag | branch | meaning |
| --- | --- | --- |
| `v0.2.0-cr132.0.6834.83` | `chromium-132-port` | this assessment; no 132 code shipped |
| `v0.1.0-cr105.0.5195.24` | `lemur-style-app-menu` | Lemur UI on the working 105 base |

## Recommendation

If the goal is *a maintained browser with Lemur UI*, finish it on
[`android-helium-browser`](../android-helium-browser) (Chromium 156, working build chain, MV2 story
already solved) instead of resurrecting Kiwi on 132. Do the 132 port only if Kiwi's specific
behaviour (Kiwi adblock, Kiwi extension bridge, bottom toolbar look) is the actual requirement.
