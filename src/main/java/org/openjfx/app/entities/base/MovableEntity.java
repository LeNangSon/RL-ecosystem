package org.openjfx.app.entities.base;

import org.openjfx.app.core.Vector2D;
import org.openjfx.app.core.WorldMap;


public abstract class MovableEntity extends Entity {
    protected Vector2D velocity;
    private Vector2D acceleration;
    private double maxSpeed;
    private double mass;
    private double maxForce;

    public MovableEntity(Vector2D position, double size, String shape,
                         double maxSpeed, double maxForce, double mass) {
        super(position, size, shape);
        this.velocity = new Vector2D(0, 0);
        this.acceleration = new Vector2D(0,0);
        this.maxForce = maxForce;
        this.maxSpeed = maxSpeed;
        this.mass = mass;
    }

    public double getMaxSpeed() {
        return maxSpeed;
    }

    public void setMaxSpeed(double maxSpeed) {
        if (maxSpeed >= 0) {
            this.maxSpeed = maxSpeed;
        }
    }


    @Override
    public void update(double dt, WorldMap world) {
        move(dt);
    }

    public void move(double dt) {
        this.position = this.position.add(this.velocity.multiply(dt));  // pos_new = pos_old + v * dt
    }

    public Vector2D getVelocity() {
        return velocity;
    }

    public void setVelocity(Vector2D velocity) {
        this.velocity = velocity;
    }

    public double getMass() {
        return mass;
    }
    public Vector2D getAcceleration() {
        return acceleration;
    }

    public void setAcceleration(Vector2D acceleration) {
        this.acceleration = acceleration;
    }

    public double getMaxForce() {
        return maxForce;
    }
}