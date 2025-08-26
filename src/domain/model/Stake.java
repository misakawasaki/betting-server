package domain.model;

public final class Stake implements Comparable<Stake> {

    public static final Stake ZERO = new Stake(0);

    private final int cents;

    private Stake(int cents) {
        this.cents = cents;
    }

    public static Stake ofCents(int cents) {
        return new Stake(cents);
    }

    public int cents() {
        return cents;
    }

    @Override
    public int compareTo(Stake o) {
        return Integer.compare(this.cents, o.cents);
    }

    @Override
    public String toString() {
        return String.valueOf(cents);
    }
}

