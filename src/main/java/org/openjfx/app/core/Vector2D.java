package org.openjfx.app.core;

public class Vector2D {
    public double x;
    public double y;

    public Vector2D(double x, double y){
        this.x = x;
        this.y = y;
    }

    public Vector2D add(Vector2D other){
        return new Vector2D(this.x + other.x, this.y + other.y);
    }
    public Vector2D sub(Vector2D other){
        return new Vector2D(this.x - other.x, this.y - other.y);
    }
    public Vector2D multiply(double scalar){
        return new Vector2D(this.x * scalar,this.y * scalar);
    }

    public double magnitude(){
        return Math.sqrt(x*x + y*y);
    }

    public Vector2D copy(Vector2D other){
        this.x = other.x;
        this.y = other.y;
        return this;
    }
    public Vector2D normalize() {
        double mag = magnitude();
        if (mag == 0) return new Vector2D(0, 0);
        return new Vector2D(x / mag, y / mag);
    }
    public Vector2D limit(double max){
        if (max <= 0) {
            return new Vector2D(0, 0);
        }

        double mag = this.magnitude();
        if (mag > max) {
            return this.normalize().multiply(max);
        }
        return this;
    }
    public Vector2D set(double x, double y){
        this.x = x;
        this.y = y;
        return this;
    }
    public double distance(Vector2D other){
        return this.sub(other).magnitude();
    }

    public double dot(Vector2D other){
        return this.x * other.x + this.y * other.y;
    }
    public Vector2D directionTo(Vector2D other) {
        return other.sub(this).normalize();
    }
    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
