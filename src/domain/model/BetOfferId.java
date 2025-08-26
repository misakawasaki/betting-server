package domain.model;

import java.util.Objects;

public final class BetOfferId {

    private final int value;

    private BetOfferId(int value) {
        this.value = value;
    }

    public static BetOfferId of(int value) {
        return new BetOfferId(value);
    }

    public int asInt() {
        return value;
    }

    @Override
    public String toString() {
        return "B" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BetOfferId that = (BetOfferId) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
