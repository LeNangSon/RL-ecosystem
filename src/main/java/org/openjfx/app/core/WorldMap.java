package org.openjfx.app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.openjfx.app.core.strategies.FleeStrategy;
import org.openjfx.app.core.strategies.HunterStrategy;
import org.openjfx.app.core.strategies.MateStrategy;
import org.openjfx.app.core.strategies.WanderStrategy;
import org.openjfx.app.core.terrain.TerrainProvider;
import org.openjfx.app.core.terrain.TerrainType;
import org.openjfx.app.entities.base.Entity;
import org.openjfx.app.entities.base.LivingEntity;
import org.openjfx.app.entities.movable.Bear;
import org.openjfx.app.entities.movable.Elephant;
import org.openjfx.app.entities.movable.Fish;
import org.openjfx.app.entities.movable.Rabbit;
import org.openjfx.app.entities.movable.Wolf;
import org.openjfx.app.entities.staticobjs.Plant;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

public class WorldMap {

    public static class GridCoordinate {
        private final int row;
        private final int col;

        public GridCoordinate(int row, int col) {
            this.row = row;
            this.col = col;
        }

        public int getRow() { return row; }
        public int getCol() { return col; }
    }

    private final double width;
    private final double height;
    private final List<Entity> entities = new ArrayList<>();
    private final List<Entity> pendingSpawns = new ArrayList<>();
    // Tập con của `entities` để tránh quét toàn bộ list ở các hàm nóng:
    //  - terrainProviders: chỉ vài vật cản đặt sẵn (Rock/Bush) -> getTerrainAt khỏi duyệt cả map.
    //  - livingEntities: chỉ con vật -> va chạm khỏi duyệt qua hàng trăm cây cỏ.
    private final List<TerrainProvider> terrainProviders = new ArrayList<>();
    private final List<LivingEntity> livingEntities = new ArrayList<>();
    private final List<GameObserver> observers = new ArrayList<>();
    private final Map<EntityType, EnumMap<DeathCause, Integer>> deathCounts = new EnumMap<>(EntityType.class);
    private TmxObjectZones tmxObjectZones;
    private double objectGridTileSize = 32.0;
    private Image fixedBackgroundImage;
    private Image transitionToImage  = null;
    private double transitionAlpha   = 0.0;
    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;

    private final Map<String, Image> imageCache = new HashMap<>();
    private final Map<Integer, String> rabbitDirectionCache = new HashMap<>();
    private final Map<Integer, String> wolfDirectionCache = new HashMap<>();
    private final Map<Integer, String> fishDirectionCache = new HashMap<>();
    private final Map<Integer, String> elephantDirectionCache = new HashMap<>();
    private final Map<Integer, String> bearDirectionCache = new HashMap<>();

    public WorldMap(double width, double height) {
        this.width = width;
        this.height = height;
    }

    public double getWidth() { return width; }
    public double getHeight() { return height; }

    public void addEntity(Entity entity) {
        if (entity == null) return;
        entities.add(entity);
        registerEntity(entity);
    }

    // Đăng ký vào các tập con tra cứu nhanh khi entity được thêm vào map.
    private void registerEntity(Entity entity) {
        if (entity instanceof TerrainProvider provider) terrainProviders.add(provider);
        if (entity instanceof LivingEntity living) livingEntities.add(living);
    }

    // Gỡ entity khỏi tập con và dọn các cache tĩnh (theo id) để tránh rò rỉ bộ nhớ.
    private void unregisterEntity(Entity entity) {
        if (entity instanceof TerrainProvider provider) terrainProviders.remove(provider);
        if (entity instanceof LivingEntity living) livingEntities.remove(living);
        purgeEntityCaches(entity.getId());
    }

    // Entity đã chết/bị xóa: xóa luôn entry của nó trong các cache hướng đi và debug path
    // (trước đây giữ mãi -> map phình to dần theo số lần sinh/tử -> áp lực GC -> giật).
    private void purgeEntityCaches(int id) {
        Integer key = id;
        rabbitDirectionCache.remove(key);
        wolfDirectionCache.remove(key);
        fishDirectionCache.remove(key);
        elephantDirectionCache.remove(key);
        bearDirectionCache.remove(key);
        HunterStrategy.clearDebugPathState(id);
        FleeStrategy.clearDebugPathState(id);
        MateStrategy.clearDebugPathState(id);
        WanderStrategy.clearDebugState(id);
    }

    public void queueSpawn(Entity entity) {
        if (entity != null) pendingSpawns.add(entity);
    }

    public List<Entity> getEntities() {
        return Collections.unmodifiableList(entities);
    }

    public Entity getEntityById(int id) {
        for (Entity e : entities) {
            if (e.getId() == id) return e;
        }
        return null;
    }

    public void setObjectZonesFromTmxResource(String resourcePath, int tileSize) {
        setObjectZonesFromTmxResource(resourcePath, tileSize, 1.0, 1.0);
    }

    public void setObjectZonesFromTmxResource(String resourcePath, int tileSize, double scaleX, double scaleY) {
        this.tmxObjectZones = TmxObjectZones.fromResource(resourcePath, scaleX, scaleY);
        this.objectGridTileSize = tileSize * Math.max(scaleX, scaleY);
    }

    public void setObjectZonesFromTmxFile(String absolutePath, int tileSize, double scaleX, double scaleY) {
        this.tmxObjectZones = TmxObjectZones.fromFile(absolutePath, scaleX, scaleY);
        this.objectGridTileSize = tileSize * Math.max(scaleX, scaleY);
    }

    public int getCellSize() {
        return Math.max(1, (int) Math.round(objectGridTileSize));
    }

    public GridCoordinate worldToGrid(Vector2D position) {
        if (position == null) return null;
        int col = (int) (position.x / objectGridTileSize);
        int row = (int) (position.y / objectGridTileSize);
        return isGridInside(row, col) ? new GridCoordinate(row, col) : null;
    }

    public Vector2D gridToWorldCenter(int row, int col) {
        if (!isGridInside(row, col)) return null;
        return new Vector2D((col + 0.5) * objectGridTileSize, (row + 0.5) * objectGridTileSize);
    }

    private boolean isGridInside(int row, int col) {
        return row >= 0 && row < getGridRows() && col >= 0 && col < getGridCols();
    }

    private int getGridRows() { return (int) Math.ceil(height / objectGridTileSize); }
    private int getGridCols() { return (int) Math.ceil(width / objectGridTileSize); }

    private String gridKey(int row, int col) {
        return row + ":" + col;
    }

    public boolean isInside(Vector2D position) {
        return position != null
                && position.x >= 0 && position.y >= 0
                && position.x <= width && position.y <= height;
    }

    public TerrainType getTerrainAt(Vector2D position) {
        if (position == null) return TerrainType.ROCK;
        TerrainType fromPlaced = terrainFromPlacedObstacles(position);
        if (fromPlaced != null) return fromPlaced;
        if (tmxObjectZones != null) {
            if (tmxObjectZones.isObstacle(position)) return TerrainType.ROCK;
            if (tmxObjectZones.isWater(position)) return TerrainType.WATER;
            if (tmxObjectZones.isBush(position)) return TerrainType.BUSH;
        }
        return TerrainType.LAND;
    }

    private TerrainType terrainFromPlacedObstacles(Vector2D position) {
        for (TerrainProvider provider : terrainProviders) {
            if (provider.covers(position)) {
                return provider.getTerrainType();
            }
        }
        return null;
    }

    public boolean isInTrough(Vector2D position) {
        return tmxObjectZones != null && tmxObjectZones.isInTrough(position);
    }

    public boolean isTouchingTrough(Vector2D position, double radius) {
        return tmxObjectZones != null && tmxObjectZones.isTouchingTrough(position, radius);
    }

    public Vector2D findNearestTrough(Vector2D from) {
        return tmxObjectZones != null ? tmxObjectZones.findNearestTrough(from) : null;
    }

    public Vector2D findNearestTerrainPosition(Vector2D from, TerrainType targetType) {
        return findNearestTerrainPositionInRadius(from, targetType, Double.MAX_VALUE);
    }

    public Vector2D findNearestTerrainPositionInRadius(Vector2D from, TerrainType targetType, double radius) {
        if (from == null || targetType == null || radius <= 0) return null;
        Vector2D fromZones = nearestFromTmxZones(from, targetType, radius);
        Vector2D fromPlaced = nearestFromPlacedObstacles(from, targetType, radius);
        if (fromZones == null) return fromPlaced;
        if (fromPlaced == null) return fromZones;
        return from.distance(fromPlaced) < from.distance(fromZones) ? fromPlaced : fromZones;
    }

    private Vector2D nearestFromTmxZones(Vector2D from, TerrainType targetType, double radius) {
        if (tmxObjectZones == null) return null;
        if (targetType == TerrainType.WATER) return tmxObjectZones.findNearestWaterInRadius(from, radius);
        if (targetType == TerrainType.BUSH) return tmxObjectZones.findNearestBushInRadius(from, radius);
        return null;
    }

    private Vector2D nearestFromPlacedObstacles(Vector2D from, TerrainType targetType, double radius) {
        Vector2D nearest = null;
        double radiusSq = radius * radius;
        double bestSq = Double.MAX_VALUE;
        for (TerrainProvider provider : terrainProviders) {
            if (provider.getTerrainType() != targetType) continue;
            Vector2D center = provider.getPosition();
            double dx = center.x - from.x;
            double dy = center.y - from.y;
            double distSq = dx * dx + dy * dy;
            if (distSq <= radiusSq && distSq < bestSq) {
                bestSq = distSq;
                nearest = center;
            }
        }
        return nearest;
    }

    private class AstarNode implements Comparable<AstarNode> {
        int row;
        int col;
        double g = Double.MAX_VALUE;
        double h;
        AstarNode parent;

        AstarNode(int row, int col) {
            this.row = row;
            this.col = col;
        }

        double getF() { return g + h; }

        @Override
        public int compareTo(AstarNode other) {
            return Double.compare(getF(), other.getF());
        }
    }

    public List<Vector2D> findPathAStar(LivingEntity entity, Vector2D start, Vector2D target) {
        return findPathAStar(entity, start, target, null);
    }

    // --- Instrumentation A* (chỉ cho harness so sánh BrainCompare; không ảnh hưởng gameplay).
    //     Đếm tĩnh, single-thread: số lần A* thực sự chạy + tổng số node được mở rộng.
    private static long aStarCalls = 0;
    private static long aStarNodes = 0;
    public static void resetAStarCounters() { aStarCalls = 0; aStarNodes = 0; }
    public static long getAStarCalls() { return aStarCalls; }
    public static long getAStarNodes() { return aStarNodes; }

    public List<Vector2D> findPathAStar(LivingEntity entity, Vector2D start, Vector2D target, Set<String> avoidedGridKeys) {
        GridCoordinate startGrid = worldToGrid(start);
        GridCoordinate targetGrid = worldToGrid(target);
        if (entity == null || startGrid == null || targetGrid == null) return null;
        aStarCalls++;   // chỉ đếm lần A* thật sự chạy tìm đường

        int cols = getGridCols();
        int targetRow = targetGrid.getRow();
        int targetCol = targetGrid.getCol();

        // Tạo node lười (lazy) theo nhu cầu thay vì cấp phát cả lưới rows×cols mỗi
        // lần gọi -> giảm mạnh rác GC (A* được nhiều strategy gọi liên tục).
        Map<Integer, AstarNode> nodes = new HashMap<>();
        Set<Integer> visited = new HashSet<>();

        AstarNode startNode = getOrCreateNode(nodes, startGrid.getRow(), startGrid.getCol(), cols);
        startNode.g = 0;
        startNode.h = calculateHeuristic(startNode.row, startNode.col, targetRow, targetCol);

        PriorityQueue<AstarNode> open = new PriorityQueue<>();
        open.add(startNode);
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};

        while (!open.isEmpty()) {
            AstarNode current = open.poll();
            if (current.row == targetRow && current.col == targetCol) return buildPath(current);
            if (!visited.add(current.row * cols + current.col)) continue;
            aStarNodes++;   // node được mở rộng (đại diện chi phí A*)

            for (int[] dir : dirs) {
                int nr = current.row + dir[0];
                int nc = current.col + dir[1];
                if (!isGridInside(nr, nc) || visited.contains(nr * cols + nc)) continue;
                if (avoidedGridKeys != null && avoidedGridKeys.contains(gridKey(nr, nc))) continue;
                Vector2D center = gridToWorldCenter(nr, nc);
                if (!canEntityStandOnTerrain(entity, getTerrainAt(center))) continue;

                AstarNode next = getOrCreateNode(nodes, nr, nc, cols);
                double cost = (dir[0] != 0 && dir[1] != 0) ? Math.sqrt(2) : 1.0;
                double newG = current.g + cost;
                if (newG < next.g) {
                    next.parent = current;
                    next.g = newG;
                    next.h = calculateHeuristic(nr, nc, targetRow, targetCol);
                    open.add(next); // entry cũ sẽ bị bỏ qua khi poll vì đã visited
                }
            }
        }
        return null;
    }

    private AstarNode getOrCreateNode(Map<Integer, AstarNode> nodes, int row, int col, int cols) {
        return nodes.computeIfAbsent(row * cols + col, k -> new AstarNode(row, col));
    }

    private double calculateHeuristic(int row1, int col1, int row2, int col2) {
        double dx = col1 - col2;
        double dy = row1 - row2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private List<Vector2D> buildPath(AstarNode node) {
        List<Vector2D> path = new ArrayList<>();
        for (AstarNode cur = node; cur != null; cur = cur.parent) {
            path.add(gridToWorldCenter(cur.row, cur.col));
        }
        Collections.reverse(path);
        if (!path.isEmpty()) path.remove(0);
        return path;
    }

    public List<Vector2D> densifyPath(List<Vector2D> path, double maxStep) {
        if (path == null || path.size() < 2 || maxStep <= 0) return path;
        List<Vector2D> result = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            Vector2D a = path.get(i);
            Vector2D b = path.get(i + 1);
            result.add(a);
            double dist = a.distance(b);
            int segments = (int) Math.ceil(dist / maxStep);
            for (int s = 1; s < segments; s++) {
                double t = (double) s / segments;
                result.add(new Vector2D(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)));
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    public Vector2D findFarthestTerrainPositionFromThreat(Vector2D from, Vector2D threatPos,
            TerrainType targetType, double searchRadius, LivingEntity entity) {
        if (from == null || threatPos == null || targetType == null || searchRadius <= 0) return null;
        Vector2D best = null;
        double bestScore = -1;
        for (Vector2D candidate : terrainCandidatesAround(from, targetType, searchRadius)) {
            if (!canStandOn(entity, candidate)) continue;
            double score = candidate.distance(threatPos);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public Vector2D findSafestTerrainPosition(Vector2D from, List<Vector2D> threatPositions,
            TerrainType targetType, double searchRadius, LivingEntity entity) {
        if (from == null || threatPositions == null || threatPositions.isEmpty()
                || targetType == null || searchRadius <= 0) return null;
        Vector2D best = null;
        double bestScore = -1;
        for (Vector2D candidate : terrainCandidatesAround(from, targetType, searchRadius)) {
            if (!canStandOn(entity, candidate)) continue;
            double minThreatDistance = Double.MAX_VALUE;
            for (Vector2D threat : threatPositions) {
                if (threat != null) minThreatDistance = Math.min(minThreatDistance, candidate.distance(threat));
            }
            if (minThreatDistance > bestScore) {
                bestScore = minThreatDistance;
                best = candidate;
            }
        }
        return best;
    }

    private List<Vector2D> terrainCandidatesAround(Vector2D from, TerrainType targetType, double radius) {
        List<Vector2D> result = new ArrayList<>();
        GridCoordinate center = worldToGrid(from);
        if (center == null) return result;
        int tileRadius = (int) Math.ceil(radius / objectGridTileSize);
        double radiusSq = radius * radius;
        for (int row = center.getRow() - tileRadius; row <= center.getRow() + tileRadius; row++) {
            for (int col = center.getCol() - tileRadius; col <= center.getCol() + tileRadius; col++) {
                Vector2D candidate = gridToWorldCenter(row, col);
                if (candidate == null) continue;
                double dx = candidate.x - from.x;
                double dy = candidate.y - from.y;
                if (dx * dx + dy * dy <= radiusSq && getTerrainAt(candidate) == targetType) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    public boolean canStandOn(LivingEntity entity, Vector2D position) {
        if (entity == null || position == null) return false;
        if (position.x < 0 || position.y < 0 || position.x > width || position.y > height) return false;
        if (collidesWithAnotherLivingEntity(entity, position)) return false;

        double radius = Math.max(2.0, entity.getSize() * 0.35);
        double diagonal = radius * 0.7;
        Vector2D[] points = {
            position,
            new Vector2D(position.x + radius, position.y),
            new Vector2D(position.x - radius, position.y),
            new Vector2D(position.x, position.y + radius),
            new Vector2D(position.x, position.y - radius),
            new Vector2D(position.x + diagonal, position.y + diagonal),
            new Vector2D(position.x + diagonal, position.y - diagonal),
            new Vector2D(position.x - diagonal, position.y + diagonal),
            new Vector2D(position.x - diagonal, position.y - diagonal)
        };
        for (Vector2D point : points) {
            if (point.x < 0 || point.y < 0 || point.x > width || point.y > height) return false;
            if (!canEntityStandOnTerrain(entity, getTerrainAt(point))) return false;
        }
        return true;
    }

    public boolean canStandAtPoint(LivingEntity entity, Vector2D position) {
        return entity != null && canEntityStandOnTerrain(entity, getTerrainAt(position));
    }

    private boolean canEntityStandOnTerrain(LivingEntity entity, TerrainType terrain) {
        if (entity == null) return false;
        EntityType entityType = entity.getType();
        if (terrain == TerrainType.ROCK || terrain == TerrainType.PIT) return false;
        if (terrain == TerrainType.WATER) return entityType == EntityType.FISH
                                              || entityType == EntityType.ELEPHANT
                                              || entityType == EntityType.BEAR;
        if (terrain == TerrainType.BUSH) return entityType == EntityType.RABBIT;
        return entityType != EntityType.FISH;
    }

    private boolean collidesWithAnotherLivingEntity(LivingEntity movingEntity, Vector2D nextPosition) {
        double movingRadius = Math.max(4.0, movingEntity.getSize() * 0.35);
        for (LivingEntity other : livingEntities) {
            if (other == movingEntity || !other.isAlive()) continue;
            double otherRadius = Math.max(4.0, other.getSize() * 0.35);
            double minDistance = movingRadius + otherRadius;
            if (RelationManager.isPrey(other.getType(), movingEntity.getType())) minDistance *= 0.35;
            double currentDist = movingEntity.getPosition().distance(other.getPosition());
            double nextDist = nextPosition.distance(other.getPosition());
            if (currentDist < minDistance && nextDist >= currentDist) continue;
            if (nextDist < minDistance) return true;
        }
        return false;
    }

    public double getInteractionDistance(LivingEntity actor, Entity target) {
        if (actor == null || target == null) return 5.0;
        return Math.max(8.0, (actor.getSize() + target.getSize()) * 0.28);
    }

    public void update(double dt) {
        for (int i = entities.size() - 1; i >= 0; i--) {
            Entity e = entities.get(i);
            e.update(dt, this);
            if (e instanceof LivingEntity living && !living.isAlive()) {
                entities.remove(i);
                unregisterEntity(e);
            } else if (e instanceof Plant plant && plant.shouldBeRemoved()) {
                entities.remove(i);
                unregisterEntity(e);
            }
        }
        if (!pendingSpawns.isEmpty()) {
            for (Entity spawn : pendingSpawns) registerEntity(spawn);
            entities.addAll(pendingSpawns);
            pendingSpawns.clear();
        }
    }

    public List<Entity> getNeighbors(Entity owner, double radius) {
        List<Entity> result = new ArrayList<>();
        for (Entity e : entities) {
            if (e != owner && owner.getPosition().distance(e.getPosition()) <= radius) result.add(e);
        }
        return result;
    }

    public void addObserver(GameObserver observer) {
        observers.add(observer);
    }

    public void notifyAction(String actor, String action, String target) {
        for (GameObserver observer : observers) observer.onActionOccurred(actor, action, target);
    }

    public void broadcastDeath(String message) {
        for (GameObserver observer : observers) observer.onEntityDeath(message);
    }

    public void recordDeath(EntityType type, DeathCause cause) {
        if (type == null || cause == null) return;
        deathCounts.computeIfAbsent(type, key -> new EnumMap<>(DeathCause.class))
                .merge(cause, 1, Integer::sum);
    }

    public int getDeathCount(EntityType type, DeathCause cause) {
        EnumMap<DeathCause, Integer> perCause = deathCounts.get(type);
        return perCause == null ? 0 : perCause.getOrDefault(cause, 0);
    }

    public Map<EntityType, EnumMap<DeathCause, Integer>> getDeathCountsByType() {
        return Collections.unmodifiableMap(deathCounts);
    }

    public void setScale(double scale) {
        this.scale = Math.max(1.0, Math.min(3.0, scale));
    }

    public double getScale() { return scale; }

    public void setOffset(double x, double y) {
        double sw = width * scale;
        double sh = height * scale;
        offsetX = sw > width ? Math.min(0, Math.max(x, width - sw)) : (width - sw) / 2;
        offsetY = sh > height ? Math.min(0, Math.max(y, height - sh)) : (height - sh) / 2;
    }

    public double getOffsetX() { return offsetX; }
    public double getOffsetY() { return offsetY; }

    public void beginBackgroundTransition(Image toImage) {
        this.transitionToImage = toImage;
        this.transitionAlpha   = 0.0;
    }

    public void setTransitionAlpha(double alpha) {
        this.transitionAlpha = Math.min(1.0, Math.max(0.0, alpha));
    }

    public void completeBackgroundTransition() {
        if (transitionToImage != null) {
            fixedBackgroundImage = transitionToImage;
            transitionToImage    = null;
            transitionAlpha      = 0.0;
        }
    }

    public void setFixedBackgroundImageFromResource(String resourcePath) {
        try {
            Image image = new Image(getClass().getResourceAsStream(resourcePath));
            fixedBackgroundImage = image.isError() ? null : image;
        } catch (Exception e) {
            fixedBackgroundImage = null;
        }
    }

    public void setFixedBackgroundImageFromFile(String absoluteFilePath) {
        try {
            Image image = new Image("file:" + absoluteFilePath);
            fixedBackgroundImage = image.isError() ? null : image;
        } catch (Exception e) {
            fixedBackgroundImage = null;
        }
    }

    public void render(GraphicsContext gc) {
        gc.save();
        gc.translate(offsetX, offsetY);
        gc.scale(scale, scale);

        if (fixedBackgroundImage != null) {
            gc.drawImage(fixedBackgroundImage, 0, 0, width, height);
        } else {
            drawGrassBackground(gc);
        }
        if (transitionToImage != null) {
            gc.save();
            gc.setGlobalAlpha(transitionAlpha);
            gc.drawImage(transitionToImage, 0, 0, width, height);
            gc.restore();
        }

        for (Entity entity : entities) {
            renderVisionRadius(gc, entity);
            if (entity instanceof Plant plant && plant.isRegrowing()) continue;
            renderEntityWithImage(gc, entity);
            renderWanderDebug(gc, entity);
            renderAStarPathDebug(gc, entity);
        }
        gc.restore();
    }

    private void drawGrassBackground(GraphicsContext gc) {
        int tileSize = 40;
        for (int x = 0; x < width; x += tileSize) {
            for (int y = 0; y < height; y += tileSize) {
                gc.setFill(Color.web((x / tileSize + y / tileSize) % 2 == 0 ? "#90EE90" : "#85e085"));
                gc.fillRect(x, y, tileSize, tileSize);
            }
        }
    }

    private void renderVisionRadius(GraphicsContext gc, Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.getVisionRadius() <= 0) return;
        double r = living.getVisionRadius();
        gc.save();
        gc.setLineWidth(1.2);
        gc.setStroke(Color.web("#66E0FF", 0.55));
        gc.setFill(Color.web("#66E0FF", 0.08));
        gc.fillOval(living.getPosition().x - r, living.getPosition().y - r, r * 2, r * 2);
        gc.strokeOval(living.getPosition().x - r, living.getPosition().y - r, r * 2, r * 2);
        gc.restore();
    }

    private void renderEntityWithImage(GraphicsContext gc, Entity entity) {
        if (entity instanceof Rabbit r) { renderRabbitWithAnimation(gc, r); return; }
        if (entity instanceof Wolf w) { renderWolfWithAnimation(gc, w); return; }
        if (entity instanceof Fish f) { renderFishWithAnimation(gc, f); return; }
        if (entity instanceof Elephant e) { renderElephantWithAnimation(gc, e); return; }
        if (entity instanceof Bear b) { renderBearWithAnimation(gc, b); return; }

        renderImageAtEntity(gc, entity, entity.toString().split("\\{")[0]);
    }

    private void renderRabbitWithAnimation(GraphicsContext gc, Rabbit r) {
        String dir = getDirection(r.getId(), r.getVelocity(), rabbitDirectionCache, "right");
        renderImageAtEntity(gc, r, "org/openjfx/app/rabbit_walk/rabbit_" + dir + "_" + getWalkFrame(r.getId(), r.getVelocity()) + ".png");
    }

    private void renderWolfWithAnimation(GraphicsContext gc, Wolf w) {
        String dir = getDirection(w.getId(), w.getVelocity(), wolfDirectionCache, "right");
        renderImageAtEntity(gc, w, "org/openjfx/app/wolf_walk/wolf_" + dir + "_" + getWalkFrame(w.getId(), w.getVelocity()) + ".png");
    }

    private void renderFishWithAnimation(GraphicsContext gc, Fish f) {
        String dir = getDirection(f.getId(), f.getVelocity(), fishDirectionCache, "right");
        renderImageAtEntity(gc, f, "org/openjfx/app/fish_swim/fish_" + dir + "_" + getWalkFrame(f.getId(), f.getVelocity()) + ".png");
    }

    private void renderElephantWithAnimation(GraphicsContext gc, Elephant e) {
        String dir = getDirection(e.getId(), e.getVelocity(), elephantDirectionCache, "right");
        if (e.isDrinking()) {
            String drinkDir = getDrinkingDirection(e, dir);
            renderImageAtEntity(gc, e, "org/openjfx/app/elephant_anim/elephant_drink_" + drinkDir + "_" + getLoopFrame(e.getId()) + ".png");
            return;
        }
        renderImageAtEntity(gc, e, "org/openjfx/app/elephant_anim/elephant_walk_" + dir + "_" + getWalkFrame(e.getId(), e.getVelocity()) + ".png");
    }

    private void renderBearWithAnimation(GraphicsContext gc, Bear b) {
        String dir = getDirection(b.getId(), b.getVelocity(), bearDirectionCache, "right");
        renderImageAtEntity(gc, b, "org/openjfx/app/bear_walk/bear_" + dir + "_" + getWalkFrame(b.getId(), b.getVelocity()) + ".png");
    }

    private String getDirection(int id, Vector2D vel, Map<Integer, String> cache, String def) {
        if (vel == null || vel.magnitude() < 0.5) return cache.getOrDefault(id, def);
        String dir = Math.abs(vel.x) >= Math.abs(vel.y)
                ? (vel.x >= 0 ? "right" : "left")
                : (vel.y >= 0 ? "down" : "up");
        cache.put(id, dir);
        return dir;
    }

    private int getWalkFrame(int id, Vector2D vel) {
        if (vel == null || vel.magnitude() < 0.5) return 0;
        return (int) ((System.nanoTime() / 120_000_000L + id) % 4);
    }

    private int getLoopFrame(int id) {
        return (int) ((System.nanoTime() / 160_000_000L + id) % 4);
    }

    private String getDrinkingDirection(Elephant elephant, String fallbackDirection) {
        Vector2D water = findNearestTerrainPositionInRadius(
                elephant.getPosition(),
                TerrainType.WATER,
                elephant.getVisionRadius());
        if (water == null) return fallbackDirection;
        Vector2D delta = water.sub(elephant.getPosition());
        if (Math.abs(delta.x) >= Math.abs(delta.y)) return delta.x >= 0 ? "right" : "left";
        return delta.y >= 0 ? "down" : "up";
    }

    private void renderImageAtEntity(GraphicsContext gc, Entity entity, String imagePath) {
        try {
            Image img = imageCache.computeIfAbsent(imagePath,
                    key -> new Image(getClass().getResourceAsStream("/" + key)));
            double x = entity.getPosition().x - entity.getSize() / 2;
            double y = entity.getPosition().y - entity.getSize() / 2;
            gc.drawImage(img, x, y, entity.getSize(), entity.getSize());
        } catch (Exception e) {
            gc.setFill(Color.RED);
            gc.fillOval(entity.getPosition().x - 5, entity.getPosition().y - 5, 10, 10);
        }
    }

    private void renderWanderDebug(GraphicsContext gc, Entity entity) {
        WanderStrategy.DebugWanderState state = WanderStrategy.getDebugState(entity.getId());
        if (state == null) return;
        double r = state.getWanderRadius();
        gc.save();
        gc.setLineWidth(1.5);
        gc.setStroke(Color.ORANGE);
        gc.strokeOval(state.getCircleCenter().x - r, state.getCircleCenter().y - r, r * 2, r * 2);
        gc.setStroke(Color.YELLOW);
        gc.strokeOval(state.getRandomPoint().x - 5, state.getRandomPoint().y - 5, 10, 10);
        gc.restore();
    }

    private void renderAStarPathDebug(GraphicsContext gc, Entity entity) {
        List<Vector2D> path = null;
        HunterStrategy.DebugPathState hunterPath = HunterStrategy.getDebugPathState(entity.getId());
        if (hunterPath != null) {
            path = hunterPath.getPath();
        } else {
            FleeStrategy.DebugPathState fleePath = FleeStrategy.getDebugPathState(entity.getId());
            if (fleePath != null) path = fleePath.getPath();
        }
        if (path == null || path.size() < 2) return;

        gc.save();
        gc.setLineWidth(2.0);
        gc.setStroke(Color.web("#00D4FF", 0.85));
        gc.setFill(Color.web("#00D4FF", 0.85));
        Vector2D prev = null;
        for (Vector2D pt : path) {
            if (pt == null) continue;
            if (prev != null) gc.strokeLine(prev.x, prev.y, pt.x, pt.y);
            gc.fillOval(pt.x - 2.5, pt.y - 2.5, 5, 5);
            prev = pt;
        }
        gc.restore();
    }
}
