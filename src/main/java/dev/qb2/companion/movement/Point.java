package dev.qb2.companion.movement;

public record Point(double x, double y, double z) {
  public static final Point ZERO = new Point(0, 0, 0);

  public Point add(Point b) {
    return new Point(x + b.x, y + b.y, z + b.z);
  }

  public Point scale(double k) {
    return new Point(x * k, y * k, z * k);
  }

  public Point subtract(Point b) {
    return add(b.scale(-1));
  }

  public double length() {
    return Math.sqrt(x * x + y * y + z * z);
  }

  public double distance(Point b) {
    return subtract(b).length();
  }

  public Point unit() {
    return length() < 1e-8 ? ZERO : scale(1 / length());
  }

  public Point lerp(Point other, double t) {
    return add(other.subtract(this).scale(t));
  }
}
