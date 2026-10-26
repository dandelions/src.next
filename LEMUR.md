# Lemur style UI branch (`lemur-style-app-menu`)

This branch carries a **Lemur Browser–style app menu** for Kiwi Browser, ported from
[`android-helium-browser`](../android-helium-browser)'s `patch_lemur_app_menu.py`
(Chromium 156 / Helium) to the Chromium 105 app-menu architecture that Kiwi uses.

Base: `kiwi` branch, Chromium `105.0.5195.24` (`KIWI_VERSION = 105.0.5195.33`).

## What the branch changes

| File | Change |
| --- | --- |
| `chrome/browser/ui/android/toolbar/java/res/layout/menu_button.xml` | three-dot icon → three-horizontal-lines (☰), shared by top/bottom/tablet/tab-switcher toolbars |
| `chrome/browser/ui/android/toolbar/java/res/drawable/ic_lemur_menu_lines.xml` | new hand-drawn vector for the ☰ icon |
| `chrome/browser/ui/android/appmenu/internal/java/src/.../AppMenuHandlerImpl.java` | `showLemurMenu()` — rounded bottom sheet app menu, hooked into `showAppMenu()`; `isAppMenuShowing()` / `hideAppMenu()` updated so the sheet toggles like the native popup |
| `chrome/browser/ui/android/appmenu/internal/java/src/.../AppMenuTest.java` | disables the sheet so the popup-menu integration tests keep exercising the native path |
| `patch_lemur_app_menu.py` | the applier for all of the above (idempotent, marker driven) |

### Visual result

* Rounded top corners (24dp), max width 520dp, bottom aligned, 16dp elevation, dim 0.35.
* Drag handle, then a **5-column icon grid**, an **extension grid (4 columns)**, then a
  scrollable list of the remaining items. Sheet height is capped at 78 % of the screen.
* Light palette `0xFFF7F8FA` / `0xFF1F1F1F` / accent `0xFF1A73E8`;
  dark palette `0xFF222327` / `0xFFF1F3F4` / accent `0xFF8AB4F8`.
  Night mode follows `ChromePreferenceKeys.UI_THEME_SETTING`, falling back to `uiMode`.
* Disabled items render at 50 % alpha, checkable items show a `✓` in the accent colour.

## Why this port is not a copy of the Helium patch

The Helium patch hard-codes ~14 menu entries and a fixed tile layout, because in
Chromium 156 the app menu delegate builds a `ModelList` inside
`TabbedAppMenuPropertiesDelegate`. Kiwi's menu is much larger (`main_menu.xml` has 63
items, including `adblock_id`, `extensions_id`, `night_mode_switcher_id`,
`auto_dark_web_contents_id`, `developer_tools_id`, `page_zoom_id`,
`reader_mode_prefs_id`, `translate_id`, `add_to_homescreen_id`), and Kiwi appends
running extensions with synthetic ids (`>= 999999`).

So this port is **data driven**: the sheet is generated from the `ModelList` that
`AppMenuPropertiesDelegateImpl.getMenuItems()` already returns.

Consequences:

* no hard-coded `R.id.*` — therefore no dependency on `chrome_java` from the appmenu
  internal target (a hard-coded `preferences_id` would have created a dependency cycle);
* every menu group (page / overview / tablet-empty), every Kiwi-specific item, incognito
  state, checkmarks and disabled states come along for free;
* extension icons appear with their real bitmaps, because `prepareExtensionMenu()` already
  decodes them into `AppMenuItemProperties.ICON`;
* when the model list is empty, or no `Activity` can be resolved, it falls back to the
  native popup menu.

Not ported from the Helium patch (absent in Chromium 105):

* `ExtensionsToolbarCoordinator` / `ExtensionsMenuCoordinator` / `ExtensionsMenuMediator`
  (the "4-square extensions sheet" needs the M120+ extensions toolbar, which Kiwi does not
  have — the extension grid inside the sheet replaces it);
* `ChromeSharedPreferences` (M105 uses `SharedPreferencesManager`),
  `ToolbarPositionController`, `AddressBarPreference`, `SettingsNavigationFactory`
  (M105 has no equivalent class);
* `PageZoomUtils`-based zoom stepper.

## Applying on a source tree

After a Kiwi rebase, or on any checkout that already contains the Kiwi delta:

```bash
python3 patch_lemur_app_menu.py <path-to>/chromium/src
```

The script is idempotent and repairs conflicts by marker matching. If a rebase moved an
anchor it fails loudly with the file name and anchor it was looking for, instead of
silently producing a half-patched tree.

## How close is this to the real Lemur? (third-party audit)

`Facilitat-dev/kiwi-browser`, branch `cursor/lemur-behavior-audit-a4fd`, adds a single 352-line
document (`docs/LEMUR-AUDIT.md`) — **behaviour spec only, no code** — auditing the closed-source
Lemur Browser (`com.lemurbrowser.exts`) from public sources (Play listing, Reddit/XDA, APKMirror
changelogs, privacy policies, Exodus Privacy). Their own constraint: no APK was downloaded or
decompiled, nothing copied. So there is **no code overlap with this branch**, and nothing to merge.

What it does give us is a reality check on what "Lemur style" means. Per that audit the real
product is:

* **two** overflow menus (a Play review calls the split confusing; the audit deliberately skips it
  and ships one menu);
* bottom-weighted chrome for years, with a top-address-bar mode only added in 2.7.5.007;
* a **dedicated extensions button on the navigation bar**, extension popups floating over the page,
  an extension sidebar, resizable popups;
* a tile-based new-tab page, swipe-up to reveal the address bar, AMOLED dark + "darken websites".

Implications for this branch:

* the 5-column tile grid here is a **plausible one-menu answer**, not a Lemur replica. The sheet
  layout, the hand-drawn icon set and the 5×2 arrangement came from
  `android-helium-browser`'s implementation, i.e. from one developer's reading of Lemur;
* if fidelity matters, priority should shift to **bottom toolbar + always-on** and a **dedicated
  extension entry point** before polishing the sheet. `android-helium-browser` has no such patches;
  `Facilitat-dev/kiwi-browser` does (`patches/m153/0014`–`0018`);
* keeping the sheet model-list driven was the right call either way: it re-skins whatever menu the
  delegate produces, so it can be demoted to an optional skin without touching the rest.

## Status / known gaps

* The Java in this branch is **not compile-verified in this repository** — `src.next` is a
  delta repo (8 313 files) without `build/`, `args.gn`, or depot_tools, so it cannot be
  built on its own. Verify with a real Chromium 105 checkout.
* Section labels (`扩展`, `更多`) and the `✓` glyph are string literals; move them to
  `strings.xml` before shipping a release build.
* 8 injected lines exceed the 100-column limit — run `google-java-format` before sending
  anything upstream.
* Icon-row sub-items carry `AppMenuItemProperties.ICON`; standard items that only set
  `ICON_COLOR_RES` render a placeholder glyph (`createLemurPlaceholderIcon`). Wiring those
  through `AppMenuItemIcon` is the natural follow-up.
* Tapping with a drag gesture (`startDragging == true`) intentionally keeps the native
  popup menu; only taps open the sheet.

## Not included (deliberately)

Building an APK from this branch is out of scope here — this repository has no build
harness and Kiwi's CI (`build.find.kiwi`) is private. Building requires a full Chromium 105
checkout with this delta overlaid; that is a separate piece of work.
