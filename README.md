# Loo & Water — Karoo extension

Two data fields for Hammerhead Karoo:

- **Nearest Toilet**: distance and direction to the closest open public toilet
- **Nearest Water**: distance and direction to the closest public drinking fountain, bubbler, bottle refill station or drinking-water tap

Tap either field and the Karoo starts turn-by-turn navigation to that spot. (It opens Karoo's pin screen and presses accept → Navigate → accept for you, then checks navigation started. If you're already following a route, it only opens the pin screen so your route isn't replaced.) The current targets are also marked on the map page.

## How it works

The data comes from OpenStreetMap, but the Karoo never queries OpenStreetMap directly (its public query servers are overloaded and unreliable). Instead:

1. Once a week, a GitHub Actions workflow in this repository downloads the OpenStreetMap extract for each region in `data/regions.txt`, keeps only toilets and water points, and splits them into small files ("tiles"), each covering 0.1° × 0.1° (about 11 km).
2. The tiles are published on GitHub Pages: `https://<your-username>.github.io/karoo-loo-water/`.
3. While a field is on screen, the extension downloads the tiles within 8 km of you (usually 4–9 small files), keeps them for the ride, and fetches new ones as you ride into new areas. Between downloads it re-picks the nearest open place every GPS update.

### What counts

- **Toilets**: `amenity=toilets`
- **Water**: `amenity=drinking_water`, toilets/water points/fountains tagged `drinking_water=yes`, `man_made=water_tap` with `drinking_water=yes`, and `fountain=drinking/bubbler/bottle_refill`
- Excluded: `access=private/no/customers/permit/delivery/employees/staff`, disused or abandoned
- **Indoors excluded** (nowhere to watch the bike): anything tagged `indoor=*`, `location=indoor/inside/underground` or on a `level` other than 0; anything inside a shopping centre (`shop=mall`); and anything inside a building of 200 m² or more. Smaller buildings are usually the toilet block itself, and open structures (roofs, shelters, pavilions, kiosks) never count as indoors. The counts left out are shown on the data page. Tune `MIN_INDOOR_BUILDING_M2` in `data/build_tiles.py` if needed.
- **Australian Toilet Map**: each week the build also downloads the [National Public Toilet Map](https://toiletmap.gov.au/) export from data.gov.au (falling back to `data/toiletmap.csv` if that fails). Matching toilets (within 50 m) take its opening hours; OSM toilets with no hours borrow them from a Toilet Map entry within 150 m. Entries marked currently closed, key/MLAK only, or in a shopping centre, food outlet or airport are removed. Toilet Map entries with no OSM toilet within 150 m are added. "Daylight hours" becomes sunrise–sunset; "Variable" and "Venue" hours stay unknown.
- **Open**: the `opening_hours` tag is checked against the Karoo's clock, including `sunrise-sunset` gates. Anything closing within 10 minutes counts as closed. Places with no hours or hours too complex to read are still offered, shown as `hours ?`.

"Nearest" is straight-line distance. Karoo's router takes over once you tap Navigate.

## What the field shows

| Field text | Meaning |
|---|---|
| `850 m` / `↗ · open` | Distance, direction (arrow relative to your heading), hours status. `· fee` if paid. |
| `…` / `searching` | Downloading data |
| `--` / `no GPS` | Waiting for a fix |
| `none` / `within 8 km` | Nothing open nearby |
| `--` / `offline · tap` | No connection. Tap to retry. |
| `--` / `data error · tap` | Connected, but the data couldn't be read |
| `--` / `not set up · tap` | The data URL in `gradle.properties` hasn't been filled in |

If a download fails because there's no connection, a short **No connection** popup appears once. It re-arms after the next successful download.

## One-time setup

### 1. Put the project on GitHub

1. Create a free account at [github.com](https://github.com) if you don't have one.
2. In Android Studio: **☰ → VCS (or Git) → Share Project on GitHub**. Sign in when asked.
   - Repository name: `karoo-loo-water` (the data URL depends on this name)
   - Untick **Private** (GitHub Pages is free for public repositories only)
   - Click **Share**, then **Add** to include all files.

### 2. Turn on GitHub Pages

1. On github.com, open the repository → **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **GitHub Actions**.

### 3. Build the data

1. Go to the **Actions** tab → **Build toilet & water data** → **Run workflow**.
2. It takes about 5–10 minutes. When it's green, open `https://<your-username>.github.io/karoo-loo-water/` in a browser. You should see a count of toilets and water points.

After this it rebuilds every Sunday by itself.

### 4. Point the extension at your data

1. In Android Studio open `gradle.properties` (under Gradle Scripts).
2. Replace `YOUR-GITHUB-USERNAME` with your GitHub username on the `loowater.dataUrl` line.
3. Rebuild the APK and install it.

## Adding regions

Add a Geofabrik `.osm.pbf` URL to `data/regions.txt` (browse [download.geofabrik.de](https://download.geofabrik.de/)), commit, and push. The workflow reruns automatically. Smaller extracts build faster; prefer a country over a whole continent.

## Building the APK

1. Open the folder in Android Studio and let Gradle sync. If asked about the Gradle JVM, choose **JVM 21**.
2. **☰ → Build → Generate App Bundles or APKs → Generate APKs**.
3. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

No GitHub token is needed: the karoo-ext library is included as source in `karoo-ext/` (Apache 2.0, SRAM LLC).

## Installing on the Karoo

USB is the most reliable route:

1. On the Karoo: **Settings → About**, tap **Build number** until developer mode is on, then **Developer options → USB debugging**.
2. Plug into the PC and, in Android Studio's Terminal:

   ```
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <karoo-serial> install -r app\build\outputs\apk\debug\app-debug.apk
   ```

Then on the Karoo: **Profiles → (profile) → a data page → tap a field → Loo & Water → Nearest Toilet / Nearest Water**.

## Hardware buttons

Two bonus actions, **Route to nearest toilet** and **Route to nearest water**, can be assigned to SRAM AXS buttons or Blips (Settings → Controls).

## Files

- `data/build_tiles.py`, `data/regions.txt`, `.github/workflows/build-data.yml`: the weekly data build
- `LooWaterExtension.kt`: the Karoo extension service (fields, bonus actions, map layer)
- `FinderDataType.kt`: renders a field and wires up the tap
- `TapReceiver.kt`: receives taps
- `NearbyFinder.kt`: location tracking, tile cache, choosing the nearest open place, routing
- `TileClient.kt`: downloads tiles via Karoo's network bridge
- `OpeningHours.kt`: `opening_hours` evaluator and sunrise/sunset calculation
- `Category.kt`: toilet / water definitions

Data © OpenStreetMap contributors, available under the Open Database License.
