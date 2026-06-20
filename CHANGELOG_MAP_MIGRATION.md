# Tong hop thay doi map, terrain va animation

File nay ghi lai nhung thay doi da lam khi chuyen project sang map moi 4 mua dung chung `all.tmx`.

## Muc tieu

- Dung 1 file `all.tmx` lam nguon object zones chung cho ca 4 mua.
- Moi mua co anh nen rieng:
  - `spring.jpg`
  - `summer.png`
  - `autumn.png`
  - `winter.png`
- Bo logic terrain cu dua tren `terrain.csv`, `TerrainGrid`, `TerrainTile`.
- Giu lai cac API quan trong cho di chuyen/pathfinding/them vat the de code cu khong bi vo.
- Giai conflict do merge giua nhanh cu va nhanh `spring`.

## File da them

### Map 4 mua va TMX/TSX

- `src/main/resources/org/openjfx/app/all.tmx`
  - TMX chung cho ca 4 mua.
  - Chua object groups dung lam logic map:
    - `ho`: vung nuoc.
    - `vatcan`: vat can/da.
    - `chotron`: bui cay/noi an.
- `src/main/resources/org/openjfx/app/spring.tsx`
- `src/main/resources/org/openjfx/app/summer.tsx`
- `src/main/resources/org/openjfx/app/autumn.tsx`
- `src/main/resources/org/openjfx/app/winter.tsx`
- `src/main/resources/org/openjfx/app/spring.jpg`
- `src/main/resources/org/openjfx/app/summer.png`
- `src/main/resources/org/openjfx/app/autumn.png`
- `src/main/resources/org/openjfx/app/winter.png`

### Parser TMX object zones

- `src/main/java/org/openjfx/app/core/TmxObjectZones.java`
  - Doc object groups tu `all.tmx`.
  - Tra loi cac cau hoi logic:
    - Diem nay co phai nuoc khong?
    - Diem nay co phai vat can khong?
    - Diem nay co phai bui cay khong?
    - Tim diem nuoc/bui gan nhat.

### Sprite/animation moi

- Them cac folder animation:
  - `bear_walk/`
  - `fish_swim/`
  - `rabbit_walk/`
  - `wolf_walk/`
  - `elephant_walk/`
  - `elephant_anim/`
- `elephant_anim/` gom 2 nhom:
  - `elephant_walk_*`: voi di chuyen 4 huong.
  - `elephant_drink_*`: voi uong nuoc 4 huong.
- Them cac sprite sheet/raw image tuong ung:
  - `BearWalkSheet*.png`
  - `ElephantWalkSheet*.png`
  - `FishSwimSheet*.png`
  - `RabbitWalkSheet*.png`
  - `WolfWalkSheet*.png`

## File da xoa

- `src/main/java/org/openjfx/app/core/terrain/TerrainGrid.java`
  - Khong con dung nua vi terrain lay tu `all.tmx`.
- `src/main/java/org/openjfx/app/core/terrain/TerrainTile.java`
  - Chi phuc vu `TerrainGrid`, nen xoa theo.
- `src/main/resources/org/openjfx/app/terrain.csv`
  - Nguon terrain cu, da duoc thay bang object zones trong TMX.
- `src/main/resources/org/openjfx/app/map-final.png`
  - Anh nen map cu, da thay bang 4 anh mua.
- `src/main/resources/org/openjfx/app/elephant_drink/`
  - Bo animation voi uong nuoc thu nghiem cu.
  - Da thay bang bo day du hon trong `elephant_anim/`.
- `src/main/java/org/openjfx/app/core/Environment.java`
- `src/main/java/org/openjfx/app/environment/Forest.java`
- `src/main/java/org/openjfx/app/environment/Grassland.java`
- `src/main/java/org/openjfx/app/environment/Lake.java`
  - Lop terrain/environment cu khong con duoc reference.
  - Terrain logic hien nam trong `WorldMap` + `TmxObjectZones`.
- `src/main/java/org/openjfx/app/entities/staticobjs/FruitTree.java`
  - Object cu khong con duoc tao tu UI hay logic world.
  - `EntityType.FRUIT` cung da duoc xoa khoi enum va relation/panel.
- `src/main/resources/org/openjfx/app/elephant_walk/`
  - Folder animation voi di bo cu bi trung voi frame moi trong `elephant_anim/`.
- Cac sprite sheet/raw backup khong duoc runtime load:
  - `BearWalkSheet.png`
  - `BearWalkSheet_raw.png`
  - `ElephantWalkSheet.png`
  - `ElephantWalkSheet_raw.png`
  - `ElephantWalkSheet_v2.png`
  - `ElephantWalkSheet_v2_raw.png`
  - `FishSwimSheet.png`
  - `FishSwimSheet_raw.png`
  - `RabbitWalkSheet.png`
  - `RabbitWalkSheet_raw.png`
  - `WolfWalkSheet.png`
  - `WolfWalkSheet_raw.png`
- Cac icon/static image cu khong con duoc runtime load:
  - `Rabbit.png`
  - `Fish.png`
  - `Elephant.png`
  - `bear.png`
  - `wolf.png`
- `src/main/resources/org/openjfx/app/hello-view.fxml`
  - FXML mau khong con duoc app load, vi app khoi dong bang Java code trong `MainApp`.

## File da sua chinh

### `MainApp.java`

Thay doi lon:

- Bo conflict marker.
- Bo load map cu:
  - Khong con `map-final.png`.
  - Khong con `terrain.csv`.
  - Khong con `setTerrainGridFromCsvResource`.
- Them load map moi:
  - Background mac dinh: `spring.jpg`.
  - Object zones: `all.tmx`.
  - Scale TMX tu kich thuoc source `1248x1248` ve canvas `576x576`.
- Them menu doi mua:
  - Xuan, Ha, Thu, Dong.
  - Doi mua chi doi background image, khong doi object zones.
- Giu logic them vat the:
  - Them tho, soi, gau, voi, ca.
  - Them co, tao.
  - Them bui, da.
- Khi them bui/da, project khong sua CSV nua; thay vao do goi `worldMap.setTerrainAt(...)` de tao runtime terrain override.

Tac dong logic:

- Hinh anh map thay doi theo mua.
- Nuoc/vat can/bui cay khong thay doi theo mua, vi lay chung tu `all.tmx`.
- Them vat the van duoc kiem tra bang `worldMap.canStandOn(...)`.

### `WorldMap.java`

Thay doi lon:

- Bo `TerrainGrid`.
- Them `TmxObjectZones`.
- `getTerrainAt(position)` gio lay terrain theo thu tu:
  1. Runtime terrain override do nguoi choi them, vi du bui/da.
  2. Object zones trong `all.tmx`.
  3. Mac dinh la `LAND`.
- Giu lai cac method de strategy cu van dung duoc:
  - `getTerrainAt`
  - `setTerrainAt`
  - `canStandOn`
  - `canStandAtPoint`
  - `findPathAStar`
  - `findNearestTerrainPosition`
  - `findNearestTerrainPositionInRadius`
  - `findFarthestTerrainPositionFromThreat`
  - `findSafestTerrainPosition`
  - `densifyPath`
  - `getCellSize`
- Render entity bang animation:
  - Tho: `rabbit_walk`.
  - Soi: `wolf_walk`.
  - Gau: `bear_walk`.
  - Ca: `fish_swim`.
  - Voi: `elephant_anim`.
- Voi uong nuoc:
  - Neu `Elephant.isDrinking()` thi render `elephant_drink_*`.
  - Huong uong nuoc dua tren vi tri ho gan nhat.

Tac dong logic:

- A* khong con doc grid CSV, ma tinh grid dua tren object zones TMX.
- Ca chi dung tren `WATER`.
- Rabbit co the dung trong `BUSH`.
- Dong vat tren can khong duoc vao `WATER`.
- Tat ca khong di qua `ROCK`/vat can.
- Bui/da them luc runtime co tac dong den pathfinding va spawn validation.

### `TmxObjectZones.java`

File moi, dung de:

- Parse `all.tmx`.
- Nhan dien object group bang ten da normalize:
  - `ho`
  - `vatcan`
  - `chotron`
- Tim diem gan nhat tren mep nuoc cho `SeekWaterStrategy`.
- Tim tam bui gan nhat cho `FleeStrategy`.

Tac dong logic:

- TMX tro thanh source of truth cho nuoc/vat can/bui.
- 4 mua co the dung chung logic map ma khong can 4 file terrain rieng.

### `SeekWaterStrategy.java`

Thay doi:

- Clean conflict.
- Voi/dong vat khat tim nuoc tu `WorldMap.findNearestTerrainPositionInRadius`.
- Khi den gan mep nuoc moi uong:
  - `drinkDistance = max(10, owner.size * 0.4)`.
- Path target nam sat mep nuoc nhung van tren dat:
  - `shoreOffset = max(8, owner.size * 0.35)`.
- Co cache path va bo nho vi tri nuoc cu neu tam thoi mat sight.

Tac dong logic:

- Dong vat khong uong khi con qua xa ho.
- Pathfinding tranh target nam trong nuoc lam dong vat can bi reject.
- Ca khong khat nuoc vi thirstRate = 0.

### `FleeStrategy.java`

Thay doi:

- Clean conflict.
- Tho uu tien chay vao `BUSH`.
- Neu dang trong bui va con threat gan, tho dung yen an nap.
- Ca chay ve vung `WATER` xa threat.
- Dong vat khac chay ve `LAND` xa threat.

Tac dong logic:

- Flee khong con phu thuoc `TerrainGrid`.
- Noi an/threat logic dua tren TMX object zones.

### `HunterStrategy.java`

Thay doi:

- Clean conflict.
- Dung `world.getInteractionDistance(...)` thay vi range co dinh qua nho.
- Path chasing dung A* va `densifyPath`.
- Bo qua prey dang trong `BUSH`.
- Khi prey chet vi bi san, goi `world.recordDeath(..., PREDATION)`.

Tac dong logic:

- San moi on dinh hon tren map moi.
- Prey trong bui duoc coi la dang an, predator khong target truc tiep.
- Bang thong ke tu vong van hoat dong.

### `LivingEntity.java`

Thay doi:

- Clean conflict.
- Giu `getMoveStrategy()` cho am thanh/thong ke/logic khac.
- Them `drinking` state:
  - `isDrinking()`
  - `setDrinking(...)`
  - `drink(dt)` bat flag drinking.
- Giu death tracking:
  - HUNGER
  - THIRST

Tac dong logic:

- Render co the biet entity dang uong nuoc.
- Voi co animation uong nuoc dung luc `drink(dt)` duoc goi.

### `Herbivore.java` va `Carnivore.java`

Thay doi:

- Clean conflict.
- Dung `StrategyCandidate` de chon strategy.
- Moi update reset `drinking=false`, sau do strategy neu goi `drink(dt)` se bat lai.
- Herbivore:
  - Flee khi co threat.
  - SeekWater khi khat.
  - Hunter khi doi.
  - Mate khi co dieu kien sinh san.
  - Wander mac dinh.
- Carnivore:
  - Flee khi co threat lon hon.
  - SeekWater khi khat.
  - Hunter khi doi hoac thay prey.
  - Mate khi co dieu kien sinh san.
  - Wander mac dinh.

Tac dong logic:

- Hanh vi dong vat tap trung hon va de mo rong hon.
- Khong con bi conflict giua logic if/else cu va strategy candidate.

### `Rabbit.java`, `Wolf.java`, `Bear.java`, `Elephant.java`, `Fish.java`

Thay doi:

- Clean conflict.
- Chot lai thong so entity theo map 576x576:
  - Rabbit nho, nhanh, vision lon de co co hoi tron.
  - Wolf san moi bang `HunterStrategy`.
  - Bear cham hon, to hon.
  - Elephant khat luc dau de test uong nuoc.
  - Fish song trong nuoc, thirstRate = 0.
- Fish sinh san bang `world.queueSpawn(...)` thay vi add truc tiep trong luc update.

Tac dong logic:

- Giam loi sua list entity khi dang update.
- Entity phu hop hon voi scale map moi.

## File da sua ve duong dan resource

- `src/main/resources/org/openjfx/app/all.tmx`
  - Doi `source="D:/summer.tsx"` thanh `source="summer.tsx"`.
- `src/main/resources/org/openjfx/app/summer.tsx`
  - Doi duong dan Downloads thanh `source="summer.png"`.
- `src/main/resources/org/openjfx/app/autumn.tsx`
  - Doi duong dan Downloads thanh `source="autumn.png"`.
- `src/main/resources/org/openjfx/app/winter.tsx`
  - Doi duong dan Downloads thanh `source="winter.png"`.

Tac dong logic:

- Project khong phu thuoc vao may ca nhan cua ADMIN nua.
- Khi gui project qua may khac, TSX/TMX van mo duoc tu resource local.

## File khong con can thiet nen da de xuat/da xoa

- `terrain.csv`: bo vi object zones trong `all.tmx` thay the.
- `TerrainGrid.java`, `TerrainTile.java`: bo vi khong con CSV grid.
- `map-final.png`: bo vi map nen moi la 4 anh mua.
- `elephant_drink/`: bo vi co `elephant_anim/` day du hon.
- `Environment.java` va package `environment/`: bo vi da thay bang terrain TMX.
- `FruitTree.java` va `EntityType.FRUIT`: bo vi khong con object fruit trong gameplay hien tai.
- `hello-view.fxml`: bo vi app khong dung FXML.
- Cac sprite sheet/raw/icon cu: bo vi runtime dang load frame trong cac folder animation.

## Resource con giu lai sau cleanup

Runtime hien con can cac resource sau:

- Map logic va nen 4 mua:
  - `all.tmx`
  - `spring.tsx`, `summer.tsx`, `autumn.tsx`, `winter.tsx`
  - `spring.jpg`, `summer.png`, `autumn.png`, `winter.png`
- Static object images:
  - `grass.png`
  - `algea.png`
  - `bush.png`
  - `rock.png`
- Animation frame folders:
  - `bear_walk/`
  - `elephant_anim/`
  - `fish_swim/`
  - `rabbit_walk/`
  - `wolf_walk/`

## Kiem tra da chay

Da build thanh cong bang:

```powershell
.\mvnw.cmd -q package
```

Ket qua:

- Khong con conflict marker trong `src/main`.
- Khong con reference den:
  - `TerrainGrid`
  - `terrain.csv`
  - `map-final`
  - `D:/`
  - `Downloads/ChatGPT`
- Maven build pass.

## Ket luan logic moi

Logic map hien tai la:

1. UI chon mua chi doi anh nen.
2. `all.tmx` la nguon chung cho terrain logic.
3. `TmxObjectZones` doc nuoc/vat can/bui tu TMX.
4. `WorldMap.getTerrainAt` gom TMX zones va runtime overrides.
5. Spawn/pathfinding/seek water/flee/hunter deu hoi `WorldMap`, nen khong can biet terrain den tu CSV hay TMX.

Noi ngan gon: project da chuyen tu "map anh cu + terrain CSV" sang "4 anh mua + 1 TMX chung lam logic terrain".
