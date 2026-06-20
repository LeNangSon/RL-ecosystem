package org.openjfx.app.core;

import java.io.FileInputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TmxObjectZones {
    private static final double DEFAULT_OBJECT_SIZE = 18.0;

    private final List<Zone> waterZones    = new ArrayList<>();
    private final List<Zone> obstacleZones = new ArrayList<>();
    private final List<Zone> bushZones     = new ArrayList<>();
    private final List<Zone> troughZones   = new ArrayList<>();

    public static TmxObjectZones fromFile(String absolutePath, double scaleX, double scaleY) {
        try (InputStream in = new FileInputStream(absolutePath)) {
            return parse(in, scaleX, scaleY);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load tmx from file: " + absolutePath, e);
        }
    }

    public static TmxObjectZones fromResource(String resourcePath) {
        return fromResource(resourcePath, 1.0, 1.0);
    }

    public static TmxObjectZones fromResource(String resourcePath, double scaleX, double scaleY) {
        try (InputStream in = TmxObjectZones.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalArgumentException("Cannot find tmx resource: " + resourcePath);
            return parse(in, scaleX, scaleY);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load tmx object zones from " + resourcePath, e);
        }
    }

    private static TmxObjectZones parse(InputStream in, double scaleX, double scaleY) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        document.getDocumentElement().normalize();

        TmxObjectZones zones = new TmxObjectZones();
        NodeList objectGroups = document.getElementsByTagName("objectgroup");
        for (int i = 0; i < objectGroups.getLength(); i++) {
            Node node = objectGroups.item(i);
            if (!(node instanceof Element)) continue;

            Element group = (Element) node;
            String groupName = normalizeName(group.getAttribute("name"));
            List<Zone> targetZones = null;
            if ("ho".equals(groupName)) {
                targetZones = zones.waterZones;
            } else if ("vatcan".equals(groupName)) {
                targetZones = zones.obstacleZones;
            } else if ("chotron".equals(groupName)) {
                targetZones = zones.bushZones;
            } else if (groupName.contains("chouongnuoc")) {
                targetZones = zones.troughZones;
            }
            if (targetZones == null) continue;

            NodeList objects = group.getElementsByTagName("object");
            for (int j = 0; j < objects.getLength(); j++) {
                Node objectNode = objects.item(j);
                if (objectNode instanceof Element) {
                    targetZones.add(Zone.fromElement((Element) objectNode, scaleX, scaleY));
                }
            }
        }
        return zones;
    }

    public boolean isWater(Vector2D position) {
        return contains(waterZones, position);
    }

    public boolean isObstacle(Vector2D position) {
        return contains(obstacleZones, position);
    }

    public boolean isInTrough(Vector2D position) {
        return contains(troughZones, position);
    }

    // Trả về true khi cạnh con vật (radius) chạm vào máng nước
    public boolean isTouchingTrough(Vector2D position, double radius) {
        if (position == null) return false;
        for (Zone zone : troughZones) {
            Vector2D nearest = zone.nearestPointTo(position);
            double dx = nearest.x - position.x;
            double dy = nearest.y - position.y;
            if (dx * dx + dy * dy <= radius * radius) return true;
        }
        return false;
    }

    public Vector2D findNearestTrough(Vector2D from) {
        if (from == null || troughZones.isEmpty()) return null;
        Vector2D nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Zone zone : troughZones) {
            Vector2D center = zone.center();
            double dx = center.x - from.x;
            double dy = center.y - from.y;
            double dist = dx * dx + dy * dy;
            if (dist < minDist) { minDist = dist; nearest = center; }
        }
        return nearest;
    }

    public boolean isBush(Vector2D position) {
        if (position == null) return false;
        for (Zone zone : bushZones) {
            Vector2D nearest = zone.nearestPointTo(position);
            double dx = nearest.x - position.x;
            double dy = nearest.y - position.y;
            if (dx * dx + dy * dy <= 10.0 * 10.0) return true;
        }
        return false;
    }

    public Vector2D findNearestBush(Vector2D from) {
        return findNearestBushInRadius(from, Double.MAX_VALUE);
    }

    public Vector2D findNearestBushInRadius(Vector2D from, double radius) {
        if (from == null || radius <= 0) return null;
        Vector2D nearest = null;
        double radiusSquared = radius * radius;
        double minDistanceSquared = Double.MAX_VALUE;
        for (Zone zone : bushZones) {
            // Dùng TÂM zone để thỏ pathfind vào giữa, không phải mép
            Vector2D center = zone.center();
            double dx = center.x - from.x;
            double dy = center.y - from.y;
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared <= radiusSquared && distanceSquared < minDistanceSquared) {
                minDistanceSquared = distanceSquared;
                nearest = center;
            }
        }
        return nearest;
    }

    public Vector2D findNearestWater(Vector2D from) {
        return findNearestWaterInRadius(from, Double.MAX_VALUE);
    }

    public Vector2D findNearestWaterInRadius(Vector2D from, double radius) {
        if (from == null || radius <= 0) return null;

        Vector2D nearest = null;
        double radiusSquared = radius * radius;
        double minDistanceSquared = Double.MAX_VALUE;
        for (Zone zone : waterZones) {
            Vector2D candidate = zone.nearestPointTo(from);
            double dx = candidate.x - from.x;
            double dy = candidate.y - from.y;
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared <= radiusSquared && distanceSquared < minDistanceSquared) {
                minDistanceSquared = distanceSquared;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static boolean contains(List<Zone> zones, Vector2D position) {
        if (position == null) return false;
        for (Zone zone : zones) {
            if (zone.contains(position)) return true;
        }
        return false;
    }

    private static String normalizeName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase();
    }

    private static final class Zone {
        private final double x;
        private final double y;
        private final double width;
        private final double height;

        private Zone(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private static Zone fromElement(Element object, double scaleX, double scaleY) {
            double x = parseDouble(object, "x", 0);
            double y = parseDouble(object, "y", 0);
            double width = parseDouble(object, "width", DEFAULT_OBJECT_SIZE);
            double height = parseDouble(object, "height", DEFAULT_OBJECT_SIZE);

            if (!object.hasAttribute("width") || width <= 0) {
                x -= DEFAULT_OBJECT_SIZE * 0.5;
                width = DEFAULT_OBJECT_SIZE;
            }
            if (!object.hasAttribute("height") || height <= 0) {
                y -= DEFAULT_OBJECT_SIZE * 0.5;
                height = DEFAULT_OBJECT_SIZE;
            }

            return new Zone(x * scaleX, y * scaleY, width * scaleX, height * scaleY);
        }

        private static double parseDouble(Element element, String attribute, double defaultValue) {
            if (!element.hasAttribute(attribute)) return defaultValue;
            return Double.parseDouble(element.getAttribute(attribute));
        }

        private Vector2D center() {
            return new Vector2D(x + width / 2, y + height / 2);
        }

        private boolean contains(Vector2D position) {
            return position.x >= x && position.x <= x + width
                    && position.y >= y && position.y <= y + height;
        }

        private Vector2D nearestPointTo(Vector2D position) {
            double clampedX = Math.max(x, Math.min(x + width, position.x));
            double clampedY = Math.max(y, Math.min(y + height, position.y));
            return new Vector2D(clampedX, clampedY);
        }
    }
}
